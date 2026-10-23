package dev.helmfile.intellij;

import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;

public final class HelmfileReleaseLineMarkerContributor extends RunLineMarkerContributor implements com.intellij.openapi.project.DumbAware {
    @Override public Info getInfo(@NotNull PsiElement element) {
        if (element.getFirstChild() != null) return null;
        YAMLKeyValue name = PsiTreeUtil.getParentOfType(element, YAMLKeyValue.class);
        if (name != null && name.getKeyText().equals("releases") && HelmfileSchema.ancestors(name).isEmpty()
                && HelmfileValues.supported(name.getContainingFile()) && name.getKey() != null
                && PsiTreeUtil.getDeepestFirst(name.getKey()) == element) {
            return new Info(AllIcons.RunConfigurations.TestState.Run, new AnAction[]{new HelmfileDiffAction()}, ignored -> "Diff all Helmfile releases");
        }
        // Template-only names may have no YAML value; the key is always a stable leaf anchor.
        if (name == null || !HelmfileReleaseTarget.isRelease(name) || name.getKey() == null
                || PsiTreeUtil.getDeepestFirst(name.getKey()) != element) return null;
        AnAction[] actions = Arrays.stream(HelmfileOperation.values()).map(operation -> new HelmfileReleaseAction(operation, name)).toArray(AnAction[]::new);
        return new Info(AllIcons.RunConfigurations.TestState.Run, actions, ignored -> "Run Helmfile for this release");
    }
}
