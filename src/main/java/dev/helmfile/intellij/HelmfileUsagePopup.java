package dev.helmfile.intellij;

import com.intellij.find.actions.ShowUsagesAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.psi.search.*;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;

/** Supplies search results through the public Find Usages extension and native Show Usages action. */
final class HelmfileUsagePopup {
    private final SmartPsiElementPointer<PsiElement> anchor;
    private final List<SmartPsiElementPointer<PsiElement>> declarations;
    private final String name;
    private final boolean declarationMode;

    static HelmfileUsagePopup usages(YAMLKeyValue declaration) {
        return new HelmfileUsagePopup(declaration, HelmfileSymbols.name(declaration), List.of(), false);
    }
    static HelmfileUsagePopup declarations(PsiElement source, String name, List<? extends PsiElement> declarations) {
        return new HelmfileUsagePopup(source, name, declarations, true);
    }
    private HelmfileUsagePopup(PsiElement source, String name, List<? extends PsiElement> declarations, boolean declarationMode) {
        SmartPointerManager pointers = SmartPointerManager.getInstance(source.getProject());
        this.anchor = pointers.createSmartPsiElementPointer(source);
        this.declarations = declarations.stream().map(element -> pointers.createSmartPsiElementPointer((PsiElement) element)).toList();
        this.name = name; this.declarationMode = declarationMode;
    }
    void show(Editor editor) {
        if (editor == null || editor.isDisposed() || !isValid()) return;
        ShowUsagesAction.startFindUsages(new SearchAnchor(this), JBPopupFactory.getInstance().guessBestPopupLocation(editor), editor);
    }
    List<UsageInfo> results() { return results(GlobalSearchScope.projectScope(anchor.getProject())); }
    List<UsageInfo> results(SearchScope scope) {
        if (!isValid()) return List.of();
        if (declarationMode) return declarations.stream().map(SmartPsiElementPointer::getElement).filter(Objects::nonNull)
                .filter(element -> PsiSearchScopeUtil.isInScope(scope, element)).map(element -> new UsageInfo(element, false)).toList();
        YAMLKeyValue key = HelmfileSymbols.key(anchor.getElement());
        return key == null ? List.of() : HelmfileUsageSearch.find(key, scope).stream().map(UsageInfo::new).toList();
    }
    boolean isDeclarationMode() { return declarationMode; }
    boolean isValid() { return !anchor.getProject().isDisposed() && anchor.getElement() != null; }

    /** Recognized by our Find Usages factory before any general-purpose reference search. */
    static final class SearchAnchor extends LightElement implements PsiNamedElement {
        final HelmfileUsagePopup popup;
        SearchAnchor(HelmfileUsagePopup popup) {
            super(PsiManager.getInstance(popup.anchor.getProject()), HelmfileLanguage.INSTANCE);
            this.popup = popup;
        }
        @Override public boolean isValid() { return popup.isValid(); }
        @Override public PsiFile getContainingFile() { return popup.anchor.getContainingFile(); }
        @Override public @NotNull String getName() { return popup.name; }
        @Override public @NotNull String getText() { return getName(); }
        @Override public PsiElement setName(@NotNull String name) { throw new IncorrectOperationException("A Helmfile search target cannot be renamed."); }
        @Override public String toString() { return "Helmfile search: " + getName(); }
    }
}
