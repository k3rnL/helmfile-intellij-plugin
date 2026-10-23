package dev.helmfile.intellij;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.notification.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
public final class HelmfileRepositoryService implements Disposable {
    record Query(HelmfileRepositories.Access access, String chart) {}
    record Snapshot(HelmfileChartIndex index, String error, boolean loading, long checked) {
        boolean validated() { return index != null && error == null && !loading; }
    }
    private final Project project;
    private final Map<Query, Snapshot> cache = new ConcurrentHashMap<>();
    private final Set<Query> pending = ConcurrentHashMap.newKeySet();
    private final Set<ProgressIndicator> active = ConcurrentHashMap.newKeySet();
    private final Set<Query> queued = ConcurrentHashMap.newKeySet();
    private final Object scheduling = new Object();
    private ScheduledFuture<?> automaticRefresh;
    private ScheduledFuture<?> analysisRefresh;
    private volatile boolean disposed;
    public HelmfileRepositoryService(Project project) { this.project = project; }
    static HelmfileRepositoryService get(Project project) { return project.getService(HelmfileRepositoryService.class); }
    Snapshot snapshot(Query query, boolean request) {
        Snapshot current = cache.get(query);
        if (request && !ApplicationManager.getApplication().isUnitTestMode() && HelmfileSettings.get(project).repositoryDownloads()
                && (current == null || System.currentTimeMillis() - current.checked() > (current.error() == null ? 300_000 : 60_000))) queue(query);
        return new Snapshot(current == null ? null : current.index(), current == null ? null : current.error(),
                pending.contains(query) || queued.contains(query), current == null ? 0 : current.checked());
    }
    private void queue(Query query) {
        synchronized (scheduling) {
            if (disposed || pending.contains(query)) return;
            queued.add(query);
            if (automaticRefresh == null) automaticRefresh = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                List<Query> work;
                synchronized (scheduling) { work = List.copyOf(queued); queued.clear(); automaticRefresh = null; }
                refresh(work);
            }, 200, TimeUnit.MILLISECONDS);
        }
    }
    private void restartAnalysis() {
        synchronized (scheduling) {
            if (disposed || analysisRefresh != null) return;
            analysisRefresh = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                synchronized (scheduling) { analysisRefresh = null; }
                if (!disposed) ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed && !project.isDisposed()) DaemonCodeAnalyzer.getInstance(project).restart(HelmfileRepositoryService.this);
                });
            }, 200, TimeUnit.MILLISECONDS);
        }
    }
    void refresh(Collection<Query> queries) { refresh(queries, false); }
    void refresh(Collection<Query> queries, boolean notify) {
        List<Query> work = queries.stream().filter(query -> !disposed && !query.access().url().contains("{{")
                && !query.access().username().contains("{{") && !query.access().password().contains("{{")
                && (query.access().downloadable() || query.access().oci() && !query.chart().isEmpty()))
                .filter(pending::add).toList();
        if (work.isEmpty()) return;
        new Task.Backgroundable(project, "Load Helm chart repositories", true) {
            @Override public boolean shouldStartInBackground() { return true; }
            @Override public void run(@NotNull ProgressIndicator indicator) {
                active.add(indicator);
                try {
                    for (int i = 0; i < work.size(); i++) {
                        indicator.checkCanceled();
                        if (disposed) return;
                        Query query = work.get(i);
                        indicator.setText("Loading " + query.access().name());
                        indicator.setFraction((double) i / work.size());
                        try {
                            HelmfileChartIndex index = HelmfileRepositoryDownload.load(query.access(), query.chart(), indicator);
                            if (!disposed) cache.put(query, new Snapshot(index, null, false, System.currentTimeMillis()));
                        } catch (IOException error) {
                            Snapshot previous = cache.get(query);
                            if (!disposed) cache.put(query, new Snapshot(previous == null ? null : previous.index(),
                                    "Repository unavailable; using repository names or the last downloaded index", false, System.currentTimeMillis()));
                        }
                    }
                } finally {
                    active.remove(indicator);
                    work.forEach(pending::remove);
                    restartAnalysis();
                }
            }
            @Override public void onSuccess() {
                if (!notify || disposed || project.isDisposed()) return;
                long ready = work.stream().map(cache::get).filter(Objects::nonNull).filter(Snapshot::validated).count();
                NotificationGroupManager.getInstance().getNotificationGroup("Helmfile repositories")
                        .createNotification("Helm chart repositories", "Loaded " + ready + " of " + work.size()
                                + " repository indexes or chart tag lists."
                                + (ready < work.size() ? " Unavailable repositories keep repository-name completion and previously downloaded suggestions." : ""),
                                ready == work.size() ? NotificationType.INFORMATION : NotificationType.WARNING).notify(project);
            }
            @Override public void onCancel() { work.forEach(pending::remove); }
        }.queue();
    }
    // Shared by deterministic fixture tests and successful downloads; no network needed for inspections.
    void remember(Query query, HelmfileChartIndex index) { cache.put(query, new Snapshot(index, null, false, System.currentTimeMillis())); }
    @Override public void dispose() {
        disposed = true;
        synchronized (scheduling) {
            if (automaticRefresh != null) automaticRefresh.cancel(false);
            if (analysisRefresh != null) analysisRefresh.cancel(false);
            queued.clear();
        }
        active.forEach(ProgressIndicator::cancel); cache.clear(); pending.clear();
    }
}
