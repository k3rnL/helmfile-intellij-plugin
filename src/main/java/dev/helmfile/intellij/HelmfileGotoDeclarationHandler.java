package dev.helmfile.intellij;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import org.jetbrains.yaml.psi.*;

public final class HelmfileGotoDeclarationHandler implements GotoDeclarationHandler, com.intellij.openapi.project.DumbAware {
    @Override public PsiElement[] getGotoDeclarationTargets(PsiElement source, int offset, Editor editor) {
        if (source == null) return null;
        PsiFile file = source.getContainingFile();
        if (HelmfileValues.supported(file)) {
            PsiFile template = file.getViewProvider().getPsi(HelmfileLanguage.INSTANCE);
            PsiReference reference = template == null ? null : template.findReferenceAt(offset);
            if (reference instanceof HelmfileReferences.ValueReference value) {
                var targets = java.util.Arrays.stream(value.multiResolve(false)).map(ResolveResult::getElement).toList();
                if (targets.size() < 2) return targets.toArray(PsiElement[]::new);
                String path = String.join(".", value.path);
                return new PsiElement[]{new HelmfileNavigationRequest(reference.getElement(), editor,
                        HelmfileUsagePopup.declarations(reference.getElement(), path, targets), "Show declarations of " + path).toPsi()};
            }
            YAMLFile yaml = HelmfileValues.yaml(file);
            PsiElement yamlElement = yaml == null ? null : yaml.findElementAt(offset);
            YAMLScalar scalar = com.intellij.psi.util.PsiTreeUtil.getParentOfType(yamlElement, YAMLScalar.class, false);
            if (scalar != null) {
                for (PsiReference candidate : HelmfileReferences.forScalar(scalar)) {
                    if (candidate instanceof HelmfileReferences.PathReference path
                            && candidate.getRangeInElement().shiftRight(scalar.getTextOffset()).containsOffset(offset)) {
                        var targets = java.util.Arrays.stream(path.multiResolve(false)).map(ResolveResult::getElement)
                                .filter(java.util.Objects::nonNull).toArray(PsiElement[]::new);
                        if (targets.length > 0) return targets;
                    }
                }
                var repository = HelmfileRepositories.reference(scalar);
                if (repository != null && repository.getRangeInElement().shiftRight(scalar.getTextOffset()).containsOffset(offset)) {
                    PsiElement target = repository.resolve();
                    if (target != null) return new PsiElement[]{target};
                }
                var chart = HelmfileChartNavigation.reference(scalar);
                if (chart != null && chart.getRangeInElement().shiftRight(scalar.getTextOffset()).containsOffset(offset)) {
                    PsiElement target = chart.resolve();
                    if (target != null) return new PsiElement[]{target};
                }
            }
            if (scalar != null && HelmfileTemplates.isInheritance(scalar)) {
                PsiElement target = new HelmfileTemplates.TemplateReference(scalar).resolve();
                return target == null ? null : new PsiElement[]{target};
            }
            if (yamlElement != null) source = yamlElement;
        }
        if (com.intellij.openapi.project.DumbService.isDumb(file.getProject())) return null;
        YAMLKeyValue key = HelmfileSymbols.key(source);
        if (key == null || !HelmfileSymbols.isDeclaration(key)) return null;
        return new PsiElement[]{new HelmfileNavigationRequest(key, editor, HelmfileUsagePopup.usages(key), "Show usages of " + HelmfileSymbols.name(key)).toPsi()};
    }
}
