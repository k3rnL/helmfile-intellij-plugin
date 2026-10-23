package dev.helmfile.intellij;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import java.net.URI;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.*;

public final class HelmfileRepositoryInspection extends LocalInspectionTool implements com.intellij.openapi.project.DumbAware {
    @Override public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof YAMLKeyValue key) || !HelmfileValues.supported(key.getContainingFile())) return;
                if (key.getKeyText().equals("repositories") && HelmfileSchema.ancestors(key).isEmpty()) repositories(key, holder);
                if (key.getValue() instanceof YAMLScalar scalar) {
                    if (HelmfileRepositories.isChartField(key, "chart")) chart(scalar, holder);
                    else if (HelmfileRepositories.isChartField(key, "version")) version(key, scalar, holder);
                }
            }
        };
    }
    private static void repositories(YAMLKeyValue key, ProblemsHolder holder) {
        if (!(key.getValue() instanceof YAMLSequence sequence)) return;
        var model = HelmfileRepositories.model(key.getContainingFile());
        for (YAMLSequenceItem item : sequence.getItems()) if (item.getValue() instanceof YAMLMapping mapping) {
            String name = HelmfileRepositories.value(mapping, "name"), url = HelmfileRepositories.value(mapping, "url");
            YAMLKeyValue declaration = mapping.getKeyValueByKey("name"), urlKey = mapping.getKeyValueByKey("url");
            if (name.isEmpty()) holder.registerProblem(mapping, "Helm repository requires a name");
            else if (!name.contains("{{") && name.contains("/")) holder.registerProblem(declaration, "Repository name must not contain '/'");
            if (url.isEmpty()) holder.registerProblem(urlKey == null ? mapping : urlKey, "Helm repository requires a URL");
            if (name.contains("{{") || declaration == null) continue;
            if (model.entries().stream().filter(repository -> repository.name().equals(name)).count() > 1)
                holder.registerProblem(declaration.getValue() == null ? declaration : declaration.getValue(), "Duplicate Helm repository name '" + name + "'");
            if (!url.isBlank() && !url.contains("{{")) {
                try {
                    URI uri = URI.create(url);
                    if ((url.startsWith("http:") || url.startsWith("https:")) && uri.getHost() == null) throw new IllegalArgumentException();
                } catch (IllegalArgumentException error) { holder.registerProblem(urlKey, "Invalid Helm repository URL"); }
            }
            var repository = model.named().get(name);
            if (repository != null && repository.access().downloadable()) {
                var snapshot = HelmfileRepositoryService.get(key.getProject()).snapshot(new HelmfileRepositoryService.Query(repository.access(), ""), false);
                if (snapshot.error() != null && urlKey != null) holder.registerProblem(urlKey,
                        snapshot.error(), ProblemHighlightType.WEAK_WARNING, new RefreshFix());
            }
        }
    }
    private static void chart(YAMLScalar scalar, ProblemsHolder holder) {
        String chart = HelmfileValues.pathText(scalar);
        if (chart.endsWith("/") || chart.contains("{{")) return;
        PsiFile file = scalar.getContainingFile();
        String alias = HelmfileRepositories.alias(file, chart);
        var model = HelmfileRepositories.model(file);
        if (alias != null && !model.named().containsKey(alias) && !model.incomplete()) {
            holder.registerProblem(scalar, "Repository '" + alias + "' is not declared in this Helmfile or its bases", ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
            return;
        }
        var query = HelmfileRepositories.query(file, chart);
        if (query == null) return;
        var snapshot = HelmfileRepositoryService.get(file.getProject()).snapshot(query, true);
        if (!snapshot.validated()) return;
        String name = query.access().oci() ? query.chart() : chart.substring(chart.indexOf('/') + 1);
        if (!snapshot.index().charts().containsKey(name)) holder.registerProblem(scalar,
                "Chart '" + name + "' was not found in repository '" + query.access().name() + "'", new RefreshFix());
    }
    private static void version(YAMLKeyValue key, YAMLScalar scalar, ProblemsHolder holder) {
        String version = HelmfileValues.pathText(scalar);
        // A Helm constraint such as ~1, ^2.0, 1.x, or >=1.0.0 is not an exact pin.
        if (!version.matches("v?\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")) return;
        String chart = HelmfileRepositories.chart(key.getParentMapping(), new HashSet<>());
        var query = HelmfileRepositories.query(key.getContainingFile(), chart);
        if (query == null) return;
        var snapshot = HelmfileRepositoryService.get(key.getProject()).snapshot(query, true);
        String name = query.access().oci() ? query.chart() : chart.substring(chart.indexOf('/') + 1);
        if (snapshot.validated() && snapshot.index().charts().containsKey(name) && !snapshot.index().containsVersion(name, version))
            holder.registerProblem(scalar, "Version '" + version + "' was not found for chart '" + chart + "'", new RefreshFix());
    }
    private static final class RefreshFix implements LocalQuickFix {
        @Override public @NotNull String getFamilyName() { return "Refresh Helm chart repositories"; }
        @Override public boolean startInWriteAction() { return false; }
        @Override public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            RefreshRepositoriesAction.refresh(descriptor.getPsiElement().getContainingFile());
        }
    }
}
