package dev.helmfile.intellij;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class HelmfileTypedHandler extends TypedHandlerDelegate {
    @Override public @NotNull Result beforeCharTyped(char character, @NotNull Project project, @NotNull Editor editor,
                                                    @NotNull PsiFile file, @NotNull FileType fileType) {
        if (!HelmfileValues.supported(file) || editor.getSelectionModel().hasSelection()
                || !CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) return Result.CONTINUE;
        int offset = editor.getCaretModel().getOffset();
        String text = editor.getDocument().getText();
        if (character == '}' && offset < text.length() && text.charAt(offset) == '}'
                && TemplateScanner.scan(text).stream().anyMatch(action -> action.closed() && offset >= action.bodyEnd() && offset < action.end())) {
            editor.getCaretModel().moveToOffset(offset + 1);
            return Result.STOP;
        }
        if (character != '{' || offset == 0 || text.charAt(offset - 1) != '{'
                || offset > 1 && text.charAt(offset - 2) == '{' || TemplateScanner.at(text, offset - 1) != null) return Result.CONTINUE;
        int line = text.lastIndexOf('\n', offset - 1) + 1;
        if (text.substring(line, offset).stripLeading().startsWith("#")) return Result.CONTINUE;
        editor.getDocument().insertString(offset, offset < text.length() && text.charAt(offset) == '}' ? "{}" : "{}}");
        editor.getCaretModel().moveToOffset(offset + 1);
        return Result.STOP;
    }
}
