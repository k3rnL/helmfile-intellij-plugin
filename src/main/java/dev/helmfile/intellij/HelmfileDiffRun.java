package dev.helmfile.intellij;

import com.intellij.execution.process.*;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

/** Capture the run's stdout without consuming the native console or blocking process output. */
final class HelmfileDiffRun implements ProcessListener {
    static final int MAX_OUTPUT = 32 * 1024 * 1024;
    static final Key<Integer> EXIT_CODE = Key.create("Helmfile.diff.exitCode");
    private final Project project;
    private final HelmfileCommand command;
    private final StringBuilder output = new StringBuilder();
    private boolean overflow;
    private volatile boolean cancelled;
    HelmfileDiffRun(Project project, HelmfileCommand command) { this.project = project; this.command = command; }
    void cancel() { cancelled = true; }
    @Override public synchronized void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        if (!ProcessOutputType.isStdout(outputType) || overflow) return;
        if ((long) output.length() + event.getText().length() > MAX_OUTPUT) { overflow = true; return; }
        output.append(event.getText());
    }
    @Override public void processTerminated(@NotNull ProcessEvent event) {
        if (cancelled || project.isDisposed()) return;
        String text;
        boolean tooLarge;
        synchronized (this) { text = output.toString(); tooLarge = overflow; }
        Integer recorded = event.getProcessHandler().getUserData(EXIT_CODE);
        int exitCode = recorded == null ? event.getExitCode() : recorded;
        if (exitCode != 0 && exitCode != 2) { showNotification("Helmfile diff failed", "Check the Run console for Helmfile's error output.", NotificationType.ERROR); return; }
        if (tooLarge) { showNotification("Helmfile diff is too large", "The diff exceeds 32 MiB. Review the output in the Run console or diff individual releases.", NotificationType.WARNING); return; }
        Task task = new Task.Backgroundable(project, "Prepare Helmfile diff", true) {
            private HelmfileDiffOutput.Result result;
            @Override public void run(@NotNull ProgressIndicator indicator) { result = HelmfileDiffOutput.parse(text); }
            @Override public void onSuccess() {
                if (project.isDisposed() || cancelled) return;
                if (result.resources().isEmpty()) {
                    boolean unchanged = exitCode == 0 && result.unparsed().isEmpty();
                    showNotification(unchanged ? "No Helmfile changes" : "No readable manifest diff",
                            unchanged ? "No manifest changes reported for " + command.scope() + " [" + command.environment() + "]."
                                    : exitCode == 2 && result.unparsed().isEmpty() ? "Helmfile reported changes without manifest output. Check skip-diff and suppression settings and the Run console."
                                    : "The output is not the standard helm-diff format. Review the Run console.",
                            unchanged ? NotificationType.INFORMATION : NotificationType.WARNING);
                    return;
                }
                if (result.partial()) showNotification("Helmfile diff contains incomplete output",
                        "Some context is omitted or output could not be parsed. Partial comparisons are labeled; review the Run console too.", NotificationType.WARNING);
                HelmfileDiffViewer.show(project, command, result);
            }
        };
        ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed() && !cancelled) task.queue(); });
    }
    private void showNotification(String title, String text, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) NotificationGroupManager.getInstance().getNotificationGroup("Helmfile diff")
                    .createNotification(title, FunctionCatalog.escape(text), type).notify(project);
        });
    }
}
