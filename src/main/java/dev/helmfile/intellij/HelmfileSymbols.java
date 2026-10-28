package dev.helmfile.intellij;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.yaml.psi.*;

final class HelmfileSymbols {
    static YAMLKeyValue key(PsiElement element) {
        if (element == null) return null;
        if (element instanceof YAMLKeyValue key) return key;
        YAMLKeyValue key = PsiTreeUtil.getParentOfType(element, YAMLKeyValue.class);
        if (key != null && HelmfileRepositories.isDeclaration(key) && key.getValue() != null
                && key.getValue().getTextRange().contains(element.getTextRange())) return key;
        return key != null && key.getKey() != null && key.getKey().getTextRange().contains(element.getTextRange()) ? key : null;
    }
    static String name(YAMLKeyValue key) {
        return HelmfileRepositories.isDeclaration(key) ? HelmfileRepositories.resolved(key.getContainingFile(),
                HelmfileValues.pathText((YAMLScalar) key.getValue())) : key.getKeyText();
    }
    static boolean isDeclaration(YAMLKeyValue key) {
        if (HelmfileTemplates.isDeclaration(key) || HelmfileRepositories.isDeclaration(key)) return true;
        PsiFile file = key.getContainingFile();
        // Only keys which actually supply state values should intercept YAML navigation.
        return HelmfileValues.allDefinitions(file).stream().anyMatch(definition -> definition.key().equals(key));
    }
    private HelmfileSymbols() {}
}
