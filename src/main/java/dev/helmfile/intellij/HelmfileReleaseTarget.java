package dev.helmfile.intellij;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.templateLanguages.OuterLanguageElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import org.jetbrains.yaml.psi.*;

final class HelmfileReleaseTarget {
    static YAMLKeyValue declaration(PsiElement element) {
        for (PsiElement current = element; current != null; current = current.getParent()) {
            if (current instanceof YAMLMapping mapping) {
                YAMLKeyValue name = mapping.getKeyValueByKey("name");
                if (name != null && isRelease(name)) return name;
            }
        }
        return null;
    }
    static boolean isRelease(YAMLKeyValue key) {
        return key.getKeyText().equals("name") && HelmfileSchema.ancestors(key).equals(List.of("releases"))
                && HelmfileValues.supported(key.getContainingFile());
    }
    static YAMLKeyValue at(PsiFile file, int offset) {
        if (file == null || !HelmfileValues.supported(file)) return null;
        YAMLFile yaml = HelmfileValues.yaml(file);
        if (yaml == null || yaml.getTextLength() == 0) return null;
        int position = Math.max(0, Math.min(offset, yaml.getTextLength() - 1));
        var leaf = yaml.getNode().findLeafElementAt(position);
        if (leaf == null) return null;
        PsiElement element = leaf.getPsi();
        if (element instanceof OuterLanguageElement) {
            // A template-only value can sit outside its YAML mapping, especially at EOF.
            // Recover its owner from the YAML immediately before it on the same line.
            CharSequence text = yaml.getViewProvider().getContents();
            int previous = leaf.getStartOffset() - 1;
            while (previous >= 0 && (text.charAt(previous) == ' ' || text.charAt(previous) == '\t')) previous--;
            if (previous < 0 || text.charAt(previous) == '\n' || text.charAt(previous) == '\r') return null;
            element = yaml.findElementAt(previous);
        }
        return declaration(element);
    }
    static String literal(YAMLKeyValue key) {
        if (key == null || !(key.getValue() instanceof YAMLScalar scalar)) return "";
        String value = HelmfileValues.pathText(scalar);
        return value.contains("{{") ? "" : value;
    }
    static String namespace(YAMLKeyValue name) {
        if (name == null) return "";
        YAMLKeyValue namespace = name.getParentMapping().getKeyValueByKey("namespace");
        if (namespace != null) return literal(namespace);
        YAMLDocument document = PsiTreeUtil.getParentOfType(name, YAMLDocument.class);
        if (document != null && document.getTopLevelValue() instanceof YAMLMapping mapping) return literal(mapping.getKeyValueByKey("namespace"));
        return "";
    }
    static String file(YAMLKeyValue name) {
        VirtualFile file = name.getContainingFile().getOriginalFile().getVirtualFile();
        return file == null ? "" : file.getPath();
    }
    private HelmfileReleaseTarget() {}
}
