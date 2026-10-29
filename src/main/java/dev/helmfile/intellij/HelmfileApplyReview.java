package dev.helmfile.intellij;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.*;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.util.PsiModificationTracker;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.NotNull;

/** One explicit approval for one captured command; rerunning Apply always starts a new review. */
final class HelmfileApplyReview {
    private final Project project;
    private final HelmfileCommand command;
    private final Map<String, String> variables;
    private final boolean passParent;
    private final long modifications;
    private final java.nio.file.attribute.FileTime timestamp;
    private final AtomicBoolean used = new AtomicBoolean();
    HelmfileApplyReview(Project project, HelmfileCommand command, Map<String, String> variables, boolean passParent) throws java.io.IOException {
        this.project = project; this.command = command; this.variables = Map.copyOf(variables); this.passParent = passParent;
        modifications = PsiModificationTracker.getInstance(project).getModificationCount();
        timestamp = Files.getLastModifiedTime(Path.of(command.file()));
    }
    boolean current() {
        if (project.isDisposed() || used.get() || modifications != PsiModificationTracker.getInstance(project).getModificationCount()
                || PsiDocumentManager.getInstance(project).getUncommittedDocuments().length > 0) return false;
        try { return timestamp.equals(Files.getLastModifiedTime(Path.of(command.file()))); }
        catch (java.io.IOException ignored) { return false; }
    }
    AnAction action() {
        AnAction action = new DumbAwareAction("Apply reviewed changes", "Apply " + command.scope() + " [" + command.environment() + "]", HelmfileOperation.APPLY.icon) {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
            @Override public void update(@NotNull AnActionEvent event) {
                boolean enabled = current();
                event.getPresentation().setEnabled(enabled);
                event.getPresentation().setDescription(enabled ? "Apply " + command.scope() + " [" + command.environment() + "]"
                        : "Review is no longer current. Run Apply again to review the changes.");
            }
            @Override public void actionPerformed(@NotNull AnActionEvent event) {
                if (current() && used.compareAndSet(false, true)) HelmfileReleaseAction.runReviewed(project, command, variables, passParent);
            }
        };
        action.getTemplatePresentation().putClientProperty(com.intellij.openapi.actionSystem.ex.ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
        return action;
    }
}
