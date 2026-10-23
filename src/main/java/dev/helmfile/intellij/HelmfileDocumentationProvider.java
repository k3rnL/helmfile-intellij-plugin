package dev.helmfile.intellij;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.yaml.psi.YAMLKeyValue;

public final class HelmfileDocumentationProvider extends AbstractDocumentationProvider implements com.intellij.openapi.project.DumbAware {
    static FunctionCatalog.Function functionAt(PsiFile file, int offset) {
        if (!HelmfileValues.supported(file)) return null;
        String text = file.getViewProvider().getContents().toString();
        if (offset < 0 || offset > text.length() || TemplateScanner.at(text, offset) == null) return null;
        com.intellij.lexer.Lexer lexer = new HelmfileSyntaxHighlighterFactory().getSyntaxHighlighter(file.getProject(), file.getVirtualFile()).getHighlightingLexer(); lexer.start(text);
        while (lexer.getTokenType() != null) {
            if (offset >= lexer.getTokenStart() && offset <= lexer.getTokenEnd() && HelmfileTokens.isDocumented(lexer.getTokenType())) {
                return FunctionCatalog.find(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
            }
            lexer.advance();
        }
        return null;
    }
    @Override public PsiElement getCustomDocumentationElement(Editor editor, PsiFile file, PsiElement context, int offset) {
        if (!HelmfileValues.supported(file)) return null;
        FunctionCatalog.Function function = functionAt(file, offset);
        if (function != null) return new DocElement(file.getManager(), file, function.name(), function.html());
        PsiFile template = file.getViewProvider().getPsi(HelmfileLanguage.INSTANCE);
        PsiReference reference = template == null ? null : template.findReferenceAt(offset);
        if (reference instanceof HelmfileReferences.ValueReference value && !value.definitions().isEmpty()) {
            return new DocElement(file.getManager(), file, String.join(".", value.path), valueHtml(value, file));
        }
        return null;
    }
    @Override public PsiElement getDocumentationElementForLookupItem(PsiManager manager, Object object, PsiElement element) {
        if (object instanceof FunctionCatalog.Function function) {
            return new DocElement(manager, element == null ? null : element.getContainingFile(), function.name(), function.html());
        }
        if (object instanceof HelmfileSchema.Field field) return new DocElement(manager, element == null ? null : element.getContainingFile(), field.name(), field.html());
        if (object instanceof HelmfileRepositoryCompletion.Suggestion suggestion) return new DocElement(manager,
                element == null ? null : element.getContainingFile(), suggestion.text(), "<div class='definition'><pre>"
                + FunctionCatalog.escape(suggestion.text()) + "</pre></div><div class='content'>Repository: "
                + FunctionCatalog.escape(suggestion.repository()) + "<p>" + FunctionCatalog.escape(suggestion.description()) + "</p></div>");
        if (object instanceof HelmfileValues.Definition definition) {
            String environment = definition.environment() == null ? HelmfileSettings.get(manager.getProject()).environment() : definition.environment();
            String path = definition.path().replace(HelmfileValues.SEPARATOR, ".");
            return new DocElement(manager, element == null ? null : element.getContainingFile(), path, valueHtml(definition, path, environment));
        }
        return null;
    }
    @Override public String generateDoc(PsiElement element, PsiElement original) {
        if (element instanceof DocElement doc) return doc.html;
        PsiElement context = original == null ? element : original;
        if (context == null) return null;
        PsiFile file = context.getContainingFile();
        if (!HelmfileValues.supported(file)) return null;
        FunctionCatalog.Function function = functionAt(file, context.getTextOffset());
        if (element != null && element.getContainingFile() != file) {
            FunctionCatalog.Function lookup = functionAt(element.getContainingFile(), element.getTextOffset());
            if (lookup != null) function = lookup;
        }
        if (function != null) return function.html();
        PsiFile template = file.getViewProvider().getPsi(HelmfileLanguage.INSTANCE);
        if (template != null) {
            PsiReference reference = template.findReferenceAt(context.getTextOffset());
            if (reference instanceof HelmfileReferences.ValueReference value) {
                if (!value.definitions().isEmpty()) return valueHtml(value, file);
            }
        }
        YAMLKeyValue key = PsiTreeUtil.getParentOfType(context, YAMLKeyValue.class, false);
        if (key != null && key.getKey() != null && key.getKey().getTextRange().contains(context.getTextRange())) {
            return HelmfileSchema.fields(HelmfileSchema.ancestors(key)).stream().filter(f -> f.name().equals(key.getKeyText()))
                    .findFirst().map(HelmfileSchema.Field::html).orElse(null);
        }
        return null;
    }
    private static String valueHtml(HelmfileReferences.ValueReference value, PsiFile file) {
        return value.definitions().stream().map(definition -> valueHtml(definition, String.join(".", value.path),
                definition.environment() == null ? HelmfileSettings.get(file.getProject()).environment() : definition.environment()))
                .collect(java.util.stream.Collectors.joining("<hr>"));
    }
    private static String valueHtml(HelmfileValues.Definition definition, String path, String environment) {
        return "<div class='definition'><pre>" + FunctionCatalog.escape(path)
                + "</pre></div><div class='content'><pre>" + FunctionCatalog.escape(definition.preview()) + "</pre>From "
                + FunctionCatalog.escape(definition.origin()) + " · environment " + FunctionCatalog.escape(environment) + "</div>";
    }
    private static final class DocElement extends LightElement {
        private final PsiFile source;
        private final String label, html;
        DocElement(PsiManager manager, PsiFile source, String label, String html) {
            super(manager, HelmfileLanguage.INSTANCE);
            this.source = source; this.label = label; this.html = html;
        }
        @Override public PsiFile getContainingFile() { return source; }
        @Override public String getText() { return label; }
        @Override public boolean isValid() { return source == null || source.isValid(); }
        @Override public String toString() { return "Helmfile documentation: " + label; }
    }
}
