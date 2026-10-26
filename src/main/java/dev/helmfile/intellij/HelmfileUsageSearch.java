package dev.helmfile.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.yaml.psi.*;

/** Use the word index for candidates, then inspect only references owned by this plugin. */
final class HelmfileUsageSearch {
    static List<PsiReference> find(YAMLKeyValue declaration, SearchScope scope) {
        // Word-search callbacks run concurrently, including during automatic usage highlighting.
        Map<String, PsiReference> references = new ConcurrentHashMap<>();
        boolean templateDeclaration = HelmfileTemplates.isDeclaration(declaration);
        PsiSearchHelper.getInstance(declaration.getProject()).processElementsWithWord((element, offsetInElement) -> {
            ProgressManager.checkCanceled();
            PsiFile file = element.getContainingFile();
            if (file == null) return true;
            int offset = element.getTextRange().getStartOffset() + offsetInElement;
            if (!templateDeclaration && HelmfileValues.supported(file)) {
                PsiFile template = file.getViewProvider().getPsi(HelmfileLanguage.INSTANCE);
                PsiElement leaf = template == null ? null : template.findElementAt(offset);
                var atom = PsiTreeUtil.getParentOfType(leaf, HelmfileParserDefinition.Atom.class, false);
                if (atom != null) for (PsiReference reference : HelmfileReferences.forAtom(atom)) {
                    if (reference instanceof HelmfileReferences.ValueReference value && covers(reference, offset)
                            && HelmfileValues.allDefinitions(file).stream().anyMatch(definition -> definition.path().equals(HelmfileValues.key(value.path))
                            && declaration.getManager().areElementsEquivalent(definition.key(), declaration))) add(references, reference);
                }
            }
            if (templateDeclaration) {
                YAMLFile yaml = HelmfileValues.yaml(file);
                YAMLScalar scalar = yaml == null ? null : PsiTreeUtil.getParentOfType(yaml.findElementAt(offset), YAMLScalar.class, false);
                if (scalar != null && HelmfileTemplates.isInheritance(scalar)) {
                    var reference = new HelmfileTemplates.TemplateReference(scalar);
                    if (covers(reference, offset) && reference.isReferenceTo(declaration)) add(references, reference);
                }
            }
            return true;
        }, scope, declaration.getKeyText(), UsageSearchContext.ANY, true);
        return List.copyOf(references.values());
    }
    private static boolean covers(PsiReference reference, int offset) {
        return reference.getRangeInElement().shiftRight(reference.getElement().getTextOffset()).containsOffset(offset);
    }
    private static void add(Map<String, PsiReference> references, PsiReference reference) {
        PsiElement element = reference.getElement();
        TextRange range = reference.getRangeInElement().shiftRight(element.getTextOffset());
        references.putIfAbsent(element.getContainingFile().getViewProvider().getVirtualFile().getUrl() + ":" + range, reference);
    }
    private HelmfileUsageSearch() {}
}
