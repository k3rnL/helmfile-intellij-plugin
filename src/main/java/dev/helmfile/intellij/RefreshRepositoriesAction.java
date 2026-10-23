package dev.helmfile.intellij;

import com.intellij.openapi.actionSystem.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLScalar;

public final class RefreshRepositoriesAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(HelmfileValues.supported(event.getData(CommonDataKeys.PSI_FILE)));
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (HelmfileValues.supported(file)) refresh(file);
    }
    static void refresh(PsiFile file) {
        List<HelmfileRepositoryService.Query> queries = com.intellij.openapi.application.ReadAction.computeBlocking(() -> {
            Set<HelmfileRepositoryService.Query> result = new LinkedHashSet<>();
            HelmfileRepositories.model(file).entries().stream().map(HelmfileRepositories.Repository::access)
                    .filter(HelmfileRepositories.Access::downloadable).forEach(access -> result.add(new HelmfileRepositoryService.Query(access, "")));
            PsiFile yaml = HelmfileValues.yaml(file);
            if (yaml != null) for (var key : PsiTreeUtil.findChildrenOfType(yaml, org.jetbrains.yaml.psi.YAMLKeyValue.class)) {
                if (HelmfileRepositories.isChartField(key, "chart") && key.getValue() instanceof YAMLScalar scalar) {
                    var query = HelmfileRepositories.query(file, HelmfileRepositories.resolved(file, HelmfileValues.pathText(scalar)));
                    if (query != null) result.add(query);
                }
            }
            return List.copyOf(result);
        });
        HelmfileRepositoryService.get(file.getProject()).refresh(queries, true);
    }
}
