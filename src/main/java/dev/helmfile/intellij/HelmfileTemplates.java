package dev.helmfile.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.*;

final class HelmfileTemplates {
    private static final Key<CachedValue<Map<String, YAMLKeyValue>>> TEMPLATES = Key.create("helmfile.templates");
    private static final Key<CachedValue<List<SmartPsiElementPointer<PsiElement>>>> CHILDREN = Key.create("helmfile.template.children");
    static boolean isInheritance(YAMLScalar scalar) {
        List<String> path = HelmfileSchema.ancestors(scalar);
        return path.equals(List.of("releases", "inherit", "template"))
                || path.size() == 4 && path.getFirst().equals("templates") && path.get(2).equals("inherit") && path.getLast().equals("template");
    }
    static boolean isDeclaration(YAMLKeyValue key) { return HelmfileSchema.ancestors(key).equals(List.of("templates")); }
    static Map<String, YAMLKeyValue> declarations(PsiFile file) {
        PsiFile original = HelmfileValues.original(file);
        return CachedValuesManager.getManager(original.getProject()).getCachedValue(original, TEMPLATES, () -> {
            Map<String, YAMLKeyValue> result = new LinkedHashMap<>();
            collect(HelmfileValues.stateFor(original), result, new HashSet<>(), 0);
            return CachedValueProvider.Result.create(Map.copyOf(result), PsiModificationTracker.MODIFICATION_COUNT,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, HelmfileSettings.get(original.getProject()));
        }, false);
    }
    private static void collect(PsiFile file, Map<String, YAMLKeyValue> result, Set<PsiFile> visiting, int depth) {
        ProgressManager.checkCanceled();
        if (file == null || depth > 40 || !visiting.add(file)) return;
        try {
            YAMLFile yaml = HelmfileValues.yaml(file);
            if (yaml == null) return;
            for (YAMLMapping root : HelmfileValues.roots(yaml)) {
                YAMLKeyValue bases = root.getKeyValueByKey("bases");
                if (bases != null && bases.getValue() instanceof YAMLSequence sequence) {
                    for (YAMLSequenceItem item : sequence.getItems()) if (item.getValue() instanceof YAMLScalar scalar) {
                        for (VirtualFile base : HelmfileValues.files(file, HelmfileValues.pathText(scalar), HelmfileSettings.get(file.getProject()).environment())) {
                            collect(PsiManager.getInstance(file.getProject()).findFile(base), result, visiting, depth + 1);
                        }
                    }
                }
                YAMLKeyValue templates = root.getKeyValueByKey("templates");
                if (templates != null && templates.getValue() instanceof YAMLMapping mapping) {
                    for (YAMLKeyValue template : mapping.getKeyValues()) result.put(template.getKeyText(), template);
                }
            }
        } finally { visiting.remove(file); }
    }
    static YAMLKeyValue owner(YAMLScalar inheritance) {
        // inherit[].template -> inherit -> release mapping or template mapping.
        YAMLKeyValue key = PsiTreeUtil.getParentOfType(inheritance, YAMLKeyValue.class);
        if (key == null) return null;
        YAMLKeyValue inherit = PsiTreeUtil.getParentOfType(key, YAMLKeyValue.class);
        if (inherit == null || !inherit.getKeyText().equals("inherit") || !(inherit.getParent() instanceof YAMLMapping mapping)) return null;
        YAMLKeyValue parent = PsiTreeUtil.getParentOfType(mapping, YAMLKeyValue.class);
        if (parent != null && isDeclaration(parent)) return parent;
        return mapping.getKeyValueByKey("name");
    }
    static List<YAMLKeyValue> parents(YAMLKeyValue declaration) {
        YAMLMapping mapping = isDeclaration(declaration) && declaration.getValue() instanceof YAMLMapping template ? template : declaration.getParentMapping();
        if (mapping == null) return List.of();
        YAMLKeyValue inherit = mapping.getKeyValueByKey("inherit");
        if (inherit == null) return List.of();
        return PsiTreeUtil.findChildrenOfType(inherit, YAMLScalar.class).stream().filter(HelmfileTemplates::isInheritance)
                .map(scalar -> declarations(scalar.getContainingFile()).get(scalar.getTextValue())).filter(Objects::nonNull).distinct().toList();
    }
    static List<PsiElement> children(YAMLKeyValue declaration) {
        List<SmartPsiElementPointer<PsiElement>> pointers = CachedValuesManager.getManager(declaration.getProject()).getCachedValue(declaration, CHILDREN, () -> {
            var targets = HelmfileUsageSearch.find(declaration, com.intellij.psi.search.GlobalSearchScope.projectScope(declaration.getProject())).stream()
                    .filter(reference -> reference.getElement() instanceof YAMLScalar).map(reference -> owner((YAMLScalar) reference.getElement()))
                    .filter(Objects::nonNull).map(YAMLKeyValue::getKey).filter(Objects::nonNull).distinct()
                    .map(SmartPointerManager::createPointer).toList();
            return CachedValueProvider.Result.create(targets, PsiModificationTracker.MODIFICATION_COUNT,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, HelmfileSettings.get(declaration.getProject()));
        }, false);
        return pointers.stream().map(SmartPsiElementPointer::getElement).filter(Objects::nonNull).toList();
    }
    static final class TemplateReference extends PsiReferenceBase<YAMLScalar> {
        TemplateReference(YAMLScalar scalar) {
            super(scalar, new TextRange(scalar.getText().startsWith("\"") || scalar.getText().startsWith("'") ? 1 : 0,
                    scalar.getTextLength() - (scalar.getText().startsWith("\"") || scalar.getText().startsWith("'") ? 1 : 0)), true);
        }
        @Override public PsiElement resolve() { return declarations(myElement.getContainingFile()).get(myElement.getTextValue()); }
        @Override public boolean isReferenceTo(@NotNull PsiElement element) {
            YAMLKeyValue target = HelmfileSymbols.key(element);
            return target != null && myElement.getManager().areElementsEquivalent(resolve(), target);
        }
    }
    private HelmfileTemplates() {}
}
