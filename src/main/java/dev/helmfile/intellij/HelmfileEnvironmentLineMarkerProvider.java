package dev.helmfile.intellij;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;

public final class HelmfileEnvironmentLineMarkerProvider extends LineMarkerProviderDescriptor implements DumbAware {
    @Override public String getName() { return "Helmfile environment selection"; }
    @Override public Icon getIcon() { return AllIcons.Actions.Execute; }
    @Override public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (element.getFirstChild() != null) return null;
        YAMLKeyValue declaration = PsiTreeUtil.getParentOfType(element, YAMLKeyValue.class);
        if (declaration == null || !HelmfileSchema.ancestors(declaration).equals(List.of("environments"))
                || declaration.getKey() == null || declaration.getKeyText().isBlank()
                || PsiTreeUtil.getDeepestFirst(declaration.getKey()) != element
                || !HelmfileValues.isStateSource(element.getContainingFile())) return null;
        String name = declaration.getKeyText();
        boolean current = name.equals(HelmfileSettings.get(element.getProject()).environment());
        Icon icon = current ? AllIcons.General.InspectionsOK : AllIcons.Actions.Execute;
        String tooltip = current ? "Current Helmfile environment: " + name : "Switch Helmfile environment to " + name;
        var pointer = SmartPointerManager.createPointer(declaration);
        return new LineMarkerInfo<>(element, element.getTextRange(), icon, ignored -> tooltip, (event, source) -> {
            String environment = ReadAction.computeBlocking(() -> {
                YAMLKeyValue target = pointer.getElement();
                return target == null || !HelmfileSchema.ancestors(target).equals(List.of("environments")) ? null : target.getKeyText();
            });
            if (environment != null) SelectEnvironmentAction.select(pointer.getProject(), environment);
        }, GutterIconRenderer.Alignment.LEFT, () -> tooltip);
    }
}
