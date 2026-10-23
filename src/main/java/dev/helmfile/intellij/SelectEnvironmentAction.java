package dev.helmfile.intellij;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.PsiFile;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class SelectEnvironmentAction extends AnAction implements DumbAware {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null && HelmfileValues.supported(event.getData(CommonDataKeys.PSI_FILE)));
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (project == null || !HelmfileValues.supported(file)) return;
        chooser(file, "Helmfile environment", () -> {}).showInBestPositionFor(event.getDataContext());
    }
    static JBPopup chooser(PsiFile file, String title, Runnable selected) {
        Project project = file.getProject();
        var pointer = com.intellij.psi.SmartPointerManager.createPointer(file);
        List<String> names = new ArrayList<>(HelmfileValues.model(file).environments());
        Collections.sort(names);
        return JBPopupFactory.getInstance().createPopupChooserBuilder(names)
                .setTitle(title + " (current: " + HelmfileSettings.get(project).environment() + ")")
                .setItemChosenCallback(name -> {
                    if (project.isDisposed() || pointer.getElement() == null) return;
                    HelmfileSettings.get(project).select(name);
                    DaemonCodeAnalyzer.getInstance(project).restart(SelectEnvironmentAction.class);
                    selected.run();
                }).createPopup();
    }
}
