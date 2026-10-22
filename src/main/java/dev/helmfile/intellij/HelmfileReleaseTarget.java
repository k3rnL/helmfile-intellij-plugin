package dev.helmfile.intellij;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
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
        return yaml == null ? null : declaration(yaml.findElementAt(Math.min(offset, Math.max(0, yaml.getTextLength() - 1))));
    }
    static String literal(YAMLKeyValue key) {
        if (key == null || !(key.getValue() instanceof YAMLScalar scalar)) return "";
        String value = HelmfileValues.pathText(scalar);
        return value.contains("{{") ? "" : value;
    }
    static String namespace(YAMLKeyValue name) {
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
