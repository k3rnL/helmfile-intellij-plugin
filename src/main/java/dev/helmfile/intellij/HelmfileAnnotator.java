package dev.helmfile.intellij;

import com.intellij.lang.annotation.*;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class HelmfileAnnotator implements Annotator, com.intellij.openapi.project.DumbAware {
    @Override public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof HelmfileParserDefinition.File)) return;
        String text = element.getText();
        Deque<TemplateScanner.Action> blocks = new ArrayDeque<>();
        for (TemplateScanner.Action action : TemplateScanner.scan(text)) {
            if (!action.closed()) {
                error(holder, action, "Unclosed Go template action; expected }}"); continue;
            }
            String body = action.body(text);
            String word = body.split("\\s+", 2)[0];
            if (Set.of("if", "range", "with", "define", "block").contains(word)) blocks.push(action);
            else if (word.equals("end")) {
                if (blocks.isEmpty()) error(holder, action, "Unexpected end without an open template block"); else blocks.pop();
            } else if (word.equals("else") && blocks.isEmpty()) error(holder, action, "Unexpected else without an open template block");
        }
        for (TemplateScanner.Action action : blocks) error(holder, action, "Template block is missing {{ end }}");
    }
    private static void error(AnnotationHolder holder, TemplateScanner.Action action, String message) {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(new TextRange(action.start(), action.end())).create();
    }
}
