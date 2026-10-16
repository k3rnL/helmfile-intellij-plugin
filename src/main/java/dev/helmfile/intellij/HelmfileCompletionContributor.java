package dev.helmfile.intellij;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.PsiFile;
import java.util.*;
import java.util.regex.*;
import org.jetbrains.annotations.NotNull;

public final class HelmfileCompletionContributor extends CompletionContributor {
    private static final Pattern PATH = Pattern.compile("(\\$?\\.(?:Values|StateValues|Environment\\.Values)\\.)([A-Za-z0-9_.]*)$");
    @Override public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        PsiFile file = parameters.getOriginalFile();
        if (!HelmfileValues.supported(file)) return;
        int offset = parameters.getOffset();
        String text = file.getViewProvider().getContents().toString();
        if (offset > text.length()) return;
        TemplateScanner.Action action = TemplateScanner.at(text, offset);
        if (action == null) {
            List<String> context = HelmfileSchema.completionPath(file, offset);
            if (context == null) return;
            for (HelmfileSchema.Field field : HelmfileSchema.fields(context)) {
                result.addElement(LookupElementBuilder.create(field, field.name()).withTypeText(field.type())
                        .withTailText("  " + field.description(), true).withInsertHandler((insertion, item) -> {
                            int tail = insertion.getTailOffset();
                            CharSequence chars = insertion.getDocument().getCharsSequence();
                            if (tail == chars.length() || chars.charAt(tail) != ':') {
                                insertion.getDocument().insertString(tail, ": ");
                                insertion.getEditor().getCaretModel().moveToOffset(tail + 2);
                            }
                        }));
            }
            return;
        }
        String prefix = text.substring(action.bodyStart(), offset);
        // The lexer knows whether the caret is inside a Go string/comment.
        HelmfileLexer lexer = new HelmfileLexer(); lexer.start(text);
        while (lexer.getTokenType() != null && lexer.getTokenEnd() < offset) lexer.advance();
        if (lexer.getTokenType() == HelmfileTokens.STRING || lexer.getTokenType() == HelmfileTokens.COMMENT) return;
        Matcher matcher = PATH.matcher(prefix);
        if (matcher.find()) {
            String suffix = matcher.group(2);
            int dot = suffix.lastIndexOf('.');
            List<String> parent = dot < 0 ? List.of() : Arrays.asList(suffix.substring(0, dot).split("\\."));
            String internalPrefix = parent.isEmpty() ? "" : HelmfileValues.key(parent) + HelmfileValues.SEPARATOR;
            CompletionResultSet values = result.withPrefixMatcher(suffix.substring(dot + 1));
            for (HelmfileValues.Definition definition : HelmfileValues.model(file).completionDefinitions()) {
                String path = definition.path();
                if (!path.startsWith(internalPrefix)) continue;
                String child = path.substring(internalPrefix.length());
                if (!child.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                values.addElement(LookupElementBuilder.create(definition, child).withTypeText(definition.preview())
                        .withTailText("  " + definition.sourceLabel(), true));
            }
            result.stopHere();
            return;
        }
        Matcher word = Pattern.compile("[$.A-Za-z_][A-Za-z0-9_.$]*$").matcher(prefix);
        CompletionResultSet functions = result.withPrefixMatcher(word.find() ? word.group() : "");
        for (FunctionCatalog.Function function : FunctionCatalog.all()) {
            functions.addElement(LookupElementBuilder.create(function, function.name()).withTypeText(function.family())
                    .withTailText("  " + function.signature().substring(function.name().length()), true));
        }
        for (String builtIn : List.of(".Values", ".StateValues", ".Environment.Name", ".Environment.Values", ".Environment.KubeContext", ".Release.Name", ".Release.Namespace", ".Release.Chart", ".Release.Labels", "$.Values", "$.Environment.Name")) {
            functions.addElement(LookupElementBuilder.create(builtIn).withTypeText("Helmfile context"));
        }
    }
}
