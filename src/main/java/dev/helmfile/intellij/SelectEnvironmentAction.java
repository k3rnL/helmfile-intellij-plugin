package dev.helmfile.intellij;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiFile;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class SelectEnvironmentAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null && HelmfileValues.supported(event.getData(CommonDataKeys.PSI_FILE)));
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (project == null || !HelmfileValues.supported(file)) return;
        List<String> names = new ArrayList<>(HelmfileValues.model(file).environments());
        Collections.sort(names);
        JBPopupFactory.getInstance().createPopupChooserBuilder(names)
                .setTitle("Helmfile environment (current: " + HelmfileSettings.get(project).environment() + ")")
                .setItemChosenCallback(name -> {
                    HelmfileSettings.get(project).select(name);
                    DaemonCodeAnalyzer.getInstance(project).restart();
                }).createPopup().showInBestPositionFor(event.getDataContext());
    }
}
