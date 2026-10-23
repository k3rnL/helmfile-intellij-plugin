package dev.helmfile.intellij;

import com.intellij.openapi.actionSystem.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/** File-wide diff, available outside any individual release declaration. */
public final class HelmfileDiffAction extends AnAction implements DumbAware {
    public HelmfileDiffAction() { super("Diff All Releases…", "Compare the full Helmfile in the IDE diff viewer", AllIcons.Actions.Diff); }
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
        var dialog = new HelmfileReleaseDialog(state);
        if (dialog.showAndGet()) HelmfileReleaseAction.run(event.getProject(), dialog.command());
    }
}
