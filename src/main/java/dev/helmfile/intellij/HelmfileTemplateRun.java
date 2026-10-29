package dev.helmfile.intellij;

import com.intellij.execution.process.*;
import com.intellij.notification.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

/** Render off the UI thread; retain the normal Run console and never preview failed or stopped output. */
final class HelmfileTemplateRun implements ProcessListener {
    static final int MAX_OUTPUT = 32 * 1024 * 1024;
    private final Project project;
    private final HelmfileCommand command;
    private final StringBuilder output = new StringBuilder();
    private boolean overflow;
    private volatile boolean cancelled;
    HelmfileTemplateRun(Project project, HelmfileCommand command) { this.project = project; this.command = command; }
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
        if (event.getExitCode() != 0) { showNotification("Helmfile template failed", "Check the Run console for Helmfile's error output.", NotificationType.ERROR); return; }
        if (tooLarge) { showNotification("Rendered manifests are too large", "The output exceeds 32 MiB. Review the Run console or narrow the release selector.", NotificationType.WARNING); return; }
        Task task = new Task.Backgroundable(project, "Prepare Helmfile manifests", true) {
            private HelmfileTemplateOutput.Result result;
            @Override public void run(@NotNull ProgressIndicator indicator) { result = HelmfileTemplateOutput.parse(text); }
            @Override public void onSuccess() {
                if (project.isDisposed() || cancelled) return;
                if (result.resources().isEmpty()) {
                    showNotification("No rendered Kubernetes manifests", "Helmfile returned no recognizable resources for " + command.scope() + " [" + command.environment() + "]. Review the Run console.", NotificationType.INFORMATION);
                    return;
                }
                if (result.unrecognized()) showNotification("Some rendered output could not be grouped", "All manifests includes the full output. Check the Run console for non-manifest output or YAML errors.", NotificationType.WARNING);
                HelmfileTemplateViewer.show(project, command, result);
            }
        };
        ApplicationManager.getApplication().invokeLater(() -> { if (!project.isDisposed() && !cancelled) task.queue(); });
    }
    private void showNotification(String title, String text, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed() && !cancelled) NotificationGroupManager.getInstance().getNotificationGroup("Helmfile template")
                    .createNotification(title, FunctionCatalog.escape(text), type).notify(project);
        });
    }
}
