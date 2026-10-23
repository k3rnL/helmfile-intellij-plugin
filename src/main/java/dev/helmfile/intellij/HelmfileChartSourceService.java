package dev.helmfile.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.notification.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.*;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.RootsChangeRescanningInfo;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.roots.ex.ProjectRootManagerEx;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.PsiManager;
import com.intellij.util.PsiNavigateUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(name = "HelmfileChartSources", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class HelmfileChartSourceService implements Disposable, PersistentStateComponent<HelmfileChartSourceService.State> {
    public static final class State { public List<Entry> charts = new ArrayList<>(); }
    public static final class Entry {
        public String id = "";
        public String title = "";
        public Entry() {}
        Entry(String id, String title) { this.id = id; this.title = title; }
    }
    private final Project project;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<CapturingProcessHandler> processes = ConcurrentHashMap.newKeySet();
    private final Set<ProgressIndicator> indicators = ConcurrentHashMap.newKeySet();
    private final Map<String, HelmfileChartLibraryProvider.ChartLibrary> libraries = new ConcurrentHashMap<>();
    private final Map<String, Entry> saved = new ConcurrentHashMap<>();
    private final AtomicBoolean restored = new AtomicBoolean();
    private volatile boolean disposed;
    public HelmfileChartSourceService(Project project) { this.project = project; }
    static HelmfileChartSourceService get(Project project) { return project.getService(HelmfileChartSourceService.class); }
    private Path cacheRoot() { return PathManager.getSystemDir().resolve("helmfile/chart-sources").resolve(project.getLocationHash()); }
    Collection<SyntheticLibrary> libraries() {
        restore();
        return libraries.values().stream().filter(library -> library.getSourceRoots().iterator().next().isValid())
                .sorted(Comparator.comparing(HelmfileChartLibraryProvider.ChartLibrary::getPresentableText)).map(SyntheticLibrary.class::cast).toList();
    }
    @Override public @NotNull State getState() { State state = new State(); state.charts.addAll(saved.values()); return state; }
    @Override public void loadState(@NotNull State state) {
        saved.clear();
        libraries.clear();
        if (state.charts != null) for (Entry entry : state.charts) if (entry != null && entry.id != null && entry.id.matches("[a-f0-9]{64}")
                && entry.title != null) saved.put(entry.id, entry);
        restored.set(false);
    }
    private void restore() {
        if (disposed || saved.isEmpty() || !restored.compareAndSet(false, true)) return;
        if (libraries.keySet().containsAll(saved.keySet())) return;
        new Task.Backgroundable(project, "Load cached Helm chart folders", true) {
            private final List<HelmfileChartLibraryProvider.ChartLibrary> loaded = new ArrayList<>();
            @Override public boolean shouldStartInBackground() { return true; }
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicators.add(indicator);
                try {
                    for (Entry entry : List.copyOf(saved.values())) {
                        indicator.checkCanceled();
                        if (disposed) return;
                        Path archive = cacheRoot().resolve(entry.id + ".zip");
                        if (!Files.isRegularFile(archive)) continue;
                        VirtualFile chart = archiveChart(archive);
                        if (chart != null && !disposed) loaded.add(library(entry.id, chart));
                    }
                } finally { indicators.remove(indicator); }
            }
            @Override public void onSuccess() {
                publish(loaded.stream().filter(chart -> !libraries.containsKey(chart.id)).toList());
                if (!disposed && !project.isDisposed()) ProjectView.getInstance(project).refresh();
            }
        }.queue();
    }
    private static VirtualFile archiveChart(Path archive) {
        VirtualFile local = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(archive);
        if (local == null) return null;
        local.refresh(false, false);
        return JarFileSystem.getInstance().refreshAndFindFileByPath(archive.toString().replace('\\', '/') + "!/Chart.yaml");
    }
    void attach(String id, VirtualFile chart) {
        publish(List.of(library(id, chart)));
    }
    private static HelmfileChartLibraryProvider.ChartLibrary library(String id, VirtualFile chart) {
        String title = "Helm chart " + chart.getParent().getName();
        try (InputStream input = chart.getInputStream()) {
            var metadata = new org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder()
                    .setCodePointLimit(1_048_576).setMaxAliasesForCollections(0).build()).loadFromInputStream(input);
            if (metadata instanceof Map<?, ?> map && map.get("name") instanceof String name)
                title = "Helm chart " + name + (map.get("version") == null ? "" : " (" + map.get("version") + ")");
        } catch (IOException | org.snakeyaml.engine.v2.exceptions.YamlEngineException ignored) { }
        return new HelmfileChartLibraryProvider.ChartLibrary(id, title, chart.getParent());
    }
    private void publish(List<HelmfileChartLibraryProvider.ChartLibrary> charts) {
        if (disposed || project.isDisposed()) return;
        var changed = charts.stream().filter(chart -> !chart.equals(libraries.get(chart.id))).toList();
        if (changed.isEmpty()) return;
        // A tree refresh alone leaves DirectoryIndex unaware of newly attached sources.
        // Publish the roots on the EDT so native library filtering and indexing see the same snapshot.
        WriteAction.run(() -> ProjectRootManagerEx.getInstanceEx(project).makeRootsChange(() -> {
            for (var chart : changed) {
                libraries.put(chart.id, chart);
                saved.put(chart.id, new Entry(chart.id, chart.getPresentableText()));
            }
        }, RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED));
    }

    void open(HelmfileChartNavigation.Request request) {
        String problem = request.problem();
        if (problem != null) { failure("Environment: " + HelmfileSettings.get(project).environment() + ". " + problem); return; }
        String id = fingerprint(request);
        if (disposed || !pending.add(id)) return;
        new Task.Backgroundable(project, "Open Helm chart " + request.chart(), true) {
            private VirtualFile chart;
            private HelmfileChartLibraryProvider.ChartLibrary library;
            private String error;
            @Override public boolean shouldStartInBackground() { return true; }
            @Override public void run(@NotNull ProgressIndicator indicator) {
                indicators.add(indicator);
                try {
                    indicator.checkCanceled();
                    if (disposed) return;
                    Path archive = fetch(request, cacheRoot(), indicator);
                    indicator.checkCanceled();
                    chart = archiveChart(archive);
                    if (chart == null) error = "The downloaded chart did not contain Chart.yaml.";
                    else if (!disposed) library = library(id, chart);
                } catch (IOException exception) { error = exception.getMessage(); }
                finally { indicators.remove(indicator); pending.remove(id); }
            }
            @Override public void onSuccess() {
                if (disposed || project.isDisposed()) return;
                if (chart != null) {
                    if (library != null) publish(List.of(library));
                    ProjectView.getInstance(project).refresh();
                    var file = ReadAction.computeBlocking(() -> PsiManager.getInstance(project).findFile(chart));
                    if (file != null) PsiNavigateUtil.navigate(file);
                    ProjectView.getInstance(project).select(null, chart, false);
                }
                else if (error != null) failure(error);
            }
            @Override public void onCancel() { pending.remove(id); }
        }.queue();
    }
    Path fetch(HelmfileChartNavigation.Request request, Path cache, ProgressIndicator indicator) throws IOException {
        indicator.checkCanceled();
        if (request.problem() != null) throw new IOException(request.problem());
        Files.createDirectories(cache);
        Path result = cache.resolve(fingerprint(request) + ".zip");
        // Pinned packages are immutable; ranges and unpinned charts follow a short refresh interval.
        boolean pinned = request.version().matches("v?\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");
        if (Files.isRegularFile(result) && (pinned || System.currentTimeMillis() - Files.getLastModifiedTime(result).toMillis() < 300_000)) return result;
        Path temporary = Files.createTempDirectory(cache, "download-");
        try {
            Path content = Files.createDirectory(temporary.resolve("content"));
            indicator.setText("Downloading chart sources");
            CapturingProcessHandler handler;
            try {
                // The process caption omits authentication arguments, including in failure reports.
                handler = new CapturingProcessHandler(request.commandLine(content).createProcess(), StandardCharsets.UTF_8, "helm pull chart sources");
            } catch (ExecutionException exception) {
                throw new IOException("Cannot start Helm. Configure the Helm executable in Settings | Tools | Helmfile.");
            }
            processes.add(handler);
            try {
                if (disposed) handler.destroyProcess();
                var output = handler.runProcessWithProgressIndicator(indicator, 120_000, true);
                indicator.checkCanceled();
                if (output.isCancelled()) throw new ProcessCanceledException();
                if (output.isTimeout()) throw new IOException("The chart download timed out.");
                if (output.getExitCode() != 0) throw new IOException("Helm could not download the chart (exit code " + output.getExitCode()
                        + "). Check the repository credentials, version, and network connection.");
            } finally { processes.remove(handler); }
            List<Path> roots;
            try (var children = Files.list(content)) {
                roots = children.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(path.resolve("Chart.yaml"), LinkOption.NOFOLLOW_LINKS)).toList();
            }
            if (roots.size() != 1) throw new IOException("Helm did not produce a single chart with Chart.yaml.");
            Path archive = temporary.resolve("source.zip");
            pack(roots.getFirst(), archive, indicator);
            indicator.checkCanceled();
            Files.move(archive, result, StandardCopyOption.REPLACE_EXISTING);
            return result;
        } finally { remove(temporary); }
    }
    private static void pack(Path root, Path archive, ProgressIndicator indicator) throws IOException {
        Path realRoot = root.toRealPath();
        List<Path> paths;
        try (var files = Files.walk(root)) { paths = files.limit(10_001).toList(); }
        if (paths.size() > 10_000) throw new IOException("The chart contains more than 10,000 source entries.");
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Path path : paths) {
                indicator.checkCanceled();
                if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(realRoot)) throw new IOException("Chart sources contain an unsupported symbolic link.");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Chart sources contain an unsupported file type.");
                zip.putNextEntry(new ZipEntry(root.relativize(path).toString().replace('\\', '/')));
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        indicator.checkCanceled();
                        total += count;
                        if (total > 128L * 1024 * 1024) throw new IOException("Chart sources exceed 128 MiB.");
                        zip.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }
    private static void remove(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* An incomplete temporary download is never used as a cached chart. */ }
    }
    static String fingerprint(HelmfileChartNavigation.Request request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var access = request.access();
            for (String value : List.of(request.chart(), request.version(), request.directory(), request.executable(), request.keyring(), request.registryConfig(),
                    Boolean.toString(request.devel()), Boolean.toString(request.verify()), access == null ? "" : access.url(),
                    access == null ? "" : access.username(), access == null ? "" : access.password(),
                    access == null ? "" : access.caFile(), access == null ? "" : access.certFile(), access == null ? "" : access.keyFile(),
                    access == null ? "" : Boolean.toString(access.oci()),
                    access == null ? "" : Boolean.toString(access.skipTLSVerify()), access == null ? "" : Boolean.toString(access.passCredentials()),
                    access == null ? "" : Boolean.toString(access.plainHttp()))) {
                digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private void failure(String message) {
        if (!disposed && !project.isDisposed()) NotificationGroupManager.getInstance().getNotificationGroup("Helmfile repositories")
                .createNotification("Cannot open Helm chart", FunctionCatalog.escape(message), NotificationType.WARNING).notify(project);
    }
    @Override public void dispose() { disposed = true; indicators.forEach(ProgressIndicator::cancel); processes.forEach(CapturingProcessHandler::destroyProcess); pending.clear(); }
}
