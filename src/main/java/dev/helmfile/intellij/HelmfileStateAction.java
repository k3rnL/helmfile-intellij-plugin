package dev.helmfile.intellij;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** File-wide operations share the release workflow, with an explicit all-releases scope. */
public class HelmfileStateAction extends AnAction implements DumbAware {
    private final HelmfileOperation operation;
    HelmfileStateAction(HelmfileOperation operation) {
        super(operation.label + " All Releases…", operation.description + " for the whole Helmfile", operation.icon);
        this.operation = operation;
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    static PsiFile state(PsiFile file) {
        if (file == null || !HelmfileValues.supported(file)) return null;
        PsiFile state = HelmfileValues.stateFor(file);
        return state == null || state.getOriginalFile().getVirtualFile() == null ? null : state;
    }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && state(event.getData(CommonDataKeys.PSI_FILE)) != null);
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        PsiFile state = state(event.getData(CommonDataKeys.PSI_FILE));
        if (state == null || event.getProject() == null) return;
        var dialog = new HelmfileReleaseDialog(state, operation);
        if (dialog.showAndGet()) HelmfileReleaseAction.run(event.getProject(), dialog.command());
    }
    public static final class Template extends HelmfileStateAction { public Template() { super(HelmfileOperation.TEMPLATE); } }
    public static final class Apply extends HelmfileStateAction { public Apply() { super(HelmfileOperation.APPLY); } }
    public static final class Destroy extends HelmfileStateAction { public Destroy() { super(HelmfileOperation.DESTROY); } }
}
