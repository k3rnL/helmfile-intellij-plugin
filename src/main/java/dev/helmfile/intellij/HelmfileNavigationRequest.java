package dev.helmfile.intellij;

import com.intellij.navigation.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import com.intellij.pom.PomNamedTarget;
import com.intellij.pom.references.PomService;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

/** Lets the platform create its own PSI wrapper; Ctrl-hover never opens a popup. */
final class HelmfileNavigationRequest implements PomNamedTarget, NavigationItem {
    private final SmartPsiElementPointer<PsiElement> source;
    private final Editor editor;
    final HelmfileUsagePopup popup;
    private final String label;
    HelmfileNavigationRequest(PsiElement source, Editor editor, HelmfileUsagePopup popup, String label) {
        this.source = SmartPointerManager.getInstance(source.getProject()).createSmartPsiElementPointer(source);
        this.editor = editor; this.popup = popup; this.label = label;
    }
    PsiElement toPsi() { return PomService.convertToPsi(source.getProject(), this); }
    @Override public void navigate(boolean requestFocus) { if (canNavigate()) popup.show(editor); }
    @Override public boolean canNavigate() { return isValid() && editor != null && !editor.isDisposed(); }
    @Override public boolean canNavigateToSource() { return canNavigate(); }
    @Override public boolean isValid() { return source.getElement() != null && popup.isValid(); }
    @Override public @NotNull String getName() { return label; }
    @Override public String toString() { return label; }
    @Override public ItemPresentation getPresentation() {
        return new ItemPresentation() {
            @Override public String getPresentableText() { return label; }
            @Override public Icon getIcon(boolean unused) { return HelmfileFileType.INSTANCE.getIcon(); }
        };
    }
}
