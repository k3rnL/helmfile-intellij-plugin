package dev.helmfile.intellij;

import com.intellij.codeInsight.completion.CompletionConfidence;
import com.intellij.psi.*;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;

public final class HelmfileCompletionConfidence extends CompletionConfidence {
    @Override public @NotNull ThreeState shouldSkipAutopopup(@NotNull com.intellij.openapi.editor.Editor editor, @NotNull PsiElement context, @NotNull PsiFile file, int offset) {
        return TemplateScanner.at(file.getViewProvider().getContents(), offset) == null ? ThreeState.UNSURE : ThreeState.NO;
    }
}
