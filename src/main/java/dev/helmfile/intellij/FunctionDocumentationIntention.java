package dev.helmfile.intellij;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class FunctionDocumentationIntention extends PsiElementBaseIntentionAction {
    @Override public @NotNull String getFamilyName() { return "Helmfile function documentation"; }
    @Override public @NotNull String getText() { return "Show Go template function documentation"; }
    @Override public boolean startInWriteAction() { return false; }
    @Override public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        return editor != null && HelmfileDocumentationProvider.functionAt(element.getContainingFile(), editor.getCaretModel().getOffset()) != null;
    }
    @Override public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        ActionManager manager = ActionManager.getInstance();
        manager.tryToExecute(manager.getAction("QuickJavaDoc"), null, editor.getContentComponent(), null, true);
    }
}
