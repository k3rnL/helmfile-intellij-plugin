package dev.helmfile.intellij;

import com.intellij.navigation.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.pom.PomNamedTarget;
import com.intellij.pom.PomTarget;
import com.intellij.pom.PomTargetPsiElement;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/** Defers opening UI until navigation, so Ctrl-hover never starts a search or opens a popup. */
final class HelmfileNavigationRequest extends LightElement implements NavigatablePsiElement, PomTargetPsiElement, PomNamedTarget {
    private final SmartPsiElementPointer<PsiElement> source;
    private final Editor editor;
    final HelmfileUsagePopup popup;
    private final String label;
    HelmfileNavigationRequest(PsiElement source, Editor editor, HelmfileUsagePopup popup, String label) {
        super(source.getManager(), HelmfileLanguage.INSTANCE);
        this.source = SmartPointerManager.getInstance(source.getProject()).createSmartPsiElementPointer(source);
        this.editor = editor; this.popup = popup; this.label = label;
    }
    @Override public void navigate(boolean requestFocus) { if (canNavigate()) popup.show(editor); }
    @Override public boolean canNavigate() { return isValid() && editor != null && !editor.isDisposed(); }
    @Override public boolean canNavigateToSource() { return canNavigate(); }
    @Override public @NotNull PomTarget getTarget() { return this; }
    @Override public boolean isValid() { return source.getElement() != null && popup.isValid(); }
    @Override public PsiFile getContainingFile() { return source.getContainingFile(); }
    @Override public TextRange getTextRange() { return source.getElement() == null ? TextRange.EMPTY_RANGE : source.getElement().getTextRange(); }
    @Override public int getTextOffset() { return getTextRange().getStartOffset(); }
    @Override public @NotNull String getText() { return label; }
    @Override public @NotNull String getName() { return label; }
    @Override public String toString() { return label; }
    @Override public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override public String getPresentableText() { return label; }
            @Override public Icon getIcon(boolean unused) { return HelmfileFileType.INSTANCE.getIcon(); }
        };
    }
}
