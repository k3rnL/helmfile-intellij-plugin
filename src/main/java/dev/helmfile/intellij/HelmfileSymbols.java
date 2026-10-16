package dev.helmfile.intellij;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.yaml.psi.*;

final class HelmfileSymbols {
    static YAMLKeyValue key(PsiElement element) {
        if (element == null) return null;
        if (element instanceof YAMLKeyValue key) return key;
        YAMLKeyValue key = PsiTreeUtil.getParentOfType(element, YAMLKeyValue.class);
        return key != null && key.getKey() != null && key.getKey().getTextRange().contains(element.getTextRange()) ? key : null;
    }
    static boolean isDeclaration(YAMLKeyValue key) {
        if (HelmfileTemplates.isDeclaration(key)) return true;
        PsiFile file = key.getContainingFile();
        // Only keys which actually supply state values should intercept YAML navigation.
        return HelmfileValues.allDefinitions(file).stream().anyMatch(definition -> definition.key().equals(key));
    }
    private HelmfileSymbols() {}
}
