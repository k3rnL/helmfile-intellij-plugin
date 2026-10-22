package dev.helmfile.intellij;

import com.intellij.codeInsight.daemon.*;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.*;

public final class HelmfileInheritanceLineMarkerProvider extends RelatedItemLineMarkerProvider {
    @Override public String getName() { return "Helmfile template inheritance"; }
    @Override protected void collectNavigationMarkers(@NotNull PsiElement element, @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        if (element.getFirstChild() != null) return;
        YAMLKeyValue declaration = PsiTreeUtil.getParentOfType(element, YAMLKeyValue.class);
        if (declaration == null) return;
        boolean template = HelmfileTemplates.isDeclaration(declaration);
        boolean release = declaration.getKeyText().equals("name") && HelmfileSchema.ancestors(declaration).equals(List.of("releases"));
        PsiElement anchor = template ? declaration.getKey() : release ? declaration.getValue() : null;
        if (anchor == null || PsiTreeUtil.getDeepestFirst(anchor) != element) return;
        List<YAMLKeyValue> parents = HelmfileTemplates.parents(declaration);
        if (!parents.isEmpty()) result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridingMethod)
                .setTargets(parents).setTooltipText("Go to parent Helmfile template").setPopupTitle("Parent Helmfile templates").createLineMarkerInfo(element, (event, source) -> {
                    if (parents.size() == 1) com.intellij.util.PsiNavigateUtil.navigate(parents.getFirst());
                    else HelmfileUsagePopup.declarations(declaration, "parent templates", parents.stream().map(YAMLKeyValue::getKey).toList()).show(editor(event, declaration));
                }));
        if (template) {
            List<PsiElement> children = HelmfileUsageSearch.find(declaration, GlobalSearchScope.projectScope(declaration.getProject())).stream()
                    .filter(reference -> reference.getElement() instanceof YAMLScalar).map(reference -> HelmfileTemplates.owner((YAMLScalar) reference.getElement()))
                    .filter(Objects::nonNull).map(child -> HelmfileTemplates.isDeclaration(child) ? child.getKey() : child.getValue()).filter(Objects::nonNull).distinct().toList();
            if (!children.isEmpty()) result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
                    .setTargets(children).setTooltipText("Go to inheriting Helmfile releases or templates").setPopupTitle("Inheriting Helmfile releases and templates")
                    .createLineMarkerInfo(element, (event, source) -> HelmfileUsagePopup.usages(declaration).show(editor(event, declaration))));
        }
    }
    private static Editor editor(java.awt.event.MouseEvent event, PsiElement source) {
        Editor editor = CommonDataKeys.EDITOR.getData(DataManager.getInstance().getDataContext(event.getComponent()));
        return editor == null ? FileEditorManager.getInstance(source.getProject()).getSelectedTextEditor() : editor;
    }
}
