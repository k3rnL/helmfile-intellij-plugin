package dev.helmfile.intellij;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInsight.template.*;
import com.intellij.codeInsight.template.impl.TextExpression;
import java.util.*;

/** Native live-template variables keep completion and Tab navigation inside generated YAML blocks. */
final class HelmfileBlockCompletion {
    static void offer(List<String> path, String prefix, CompletionResultSet result) {
        if (!prefix.matches("[ \\t]*-[ \\t]*[A-Za-z_]*")) return;
        String context = HelmfileSchema.context(path);
        if (context.equals("releases") && !path.equals(List.of("releases"))) return;
        String type = switch (context) { case "releases" -> "release"; case "dependencies" -> "dependency"; case "repositories" -> "repository"; default -> null; };
        if (type != null) result.addElement(LookupElementBuilder.create(type).withTypeText("Helmfile block")
                .withTailText("  insert required fields; Tab moves between values", true)
                .withInsertHandler((insertion, item) -> insert(insertion, type, false)));
    }
    static boolean section(InsertionContext context, String field) {
        if (!Set.of("releases", "dependencies", "repositories").contains(field)) return false;
        int tail = context.getTailOffset();
        CharSequence chars = context.getDocument().getCharsSequence();
        if (tail < chars.length() && chars.charAt(tail) == ':') return false;
        insert(context, field.equals("releases") ? "release" : field.equals("repositories") ? "repository" : "dependency", true);
        return true;
    }
    private static void insert(InsertionContext context, String type, boolean section) {
        var editor = context.getEditor();
        var document = context.getDocument();
        int start = context.getStartOffset();
        int line = document.getLineStartOffset(document.getLineNumber(start));
        String prefix = document.getText().substring(line, start);
        String indentation = prefix.replaceFirst("\\S.*$", "");
        boolean dash = prefix.stripLeading().startsWith("-");
        String fieldIndent = indentation + (section ? "    " : "  ");
        String header = section ? (type.equals("release") ? "releases" : type.equals("repository") ? "repositories" : "dependencies") + ":\n" + indentation + "  - "
                : dash ? "" : "- ";
        if (!section && dash) fieldIndent = " ".repeat(prefix.length());
        String body = type.equals("repository") ? "name: $NAME$\n" + fieldIndent + "url: $URL$"
                : type.equals("release") ? "name: $NAME$\n" + fieldIndent + "chart: $CHART$\n" + fieldIndent + "version: $VERSION$"
                : "chart: $CHART$\n" + fieldIndent + "version: $VERSION$";
        Template template = TemplateManager.getInstance(context.getProject()).createTemplate("", "Helmfile", header + body + "$END$");
        template.setToReformat(false);
        template.setToIndent(false);
        if (!type.equals("dependency")) template.addVariable("NAME", new TextExpression(""), new TextExpression(""), true);
        if (type.equals("repository")) template.addVariable("URL", new TextExpression(""), new TextExpression("https://"), true);
        else {
            template.addVariable("CHART", new TextExpression(""), new TextExpression(""), true);
            template.addVariable("VERSION", new ChartVersions(), new TextExpression(""), true);
        }
        document.deleteString(start, context.getTailOffset());
        editor.getCaretModel().moveToOffset(start);
        context.setAddCompletionChar(false);
        TemplateManager.getInstance(context.getProject()).startTemplate(editor, template, new TemplateEditingAdapter() {
            @Override public void waitingForInput(Template current) {
                int offset = editor.getCaretModel().getOffset();
                String before = document.getText().substring(document.getLineStartOffset(document.getLineNumber(offset)), offset);
                if (before.matches(".*\\b(chart|version):.*")) AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(editor);
            }
        });
    }
    private static final class ChartVersions extends Expression {
        private List<HelmfileChartIndex.Chart> versions(ExpressionContext context) {
            var value = context.getVariableValue("CHART");
            var file = context.getPsiFile();
            if (value == null || file == null) return List.of();
            String chart = value.toString();
            var query = HelmfileRepositories.query(file, chart);
            if (query == null) return List.of();
            var snapshot = HelmfileRepositoryService.get(context.getProject()).snapshot(query, true);
            String name = query.access().oci() ? query.chart() : chart.substring(chart.indexOf('/') + 1);
            return snapshot.index() == null ? List.of() : snapshot.index().charts().getOrDefault(name, List.of());
        }
        @Override public Result calculateResult(ExpressionContext context) {
            var versions = versions(context);
            return new TextResult(versions.isEmpty() ? "" : versions.getFirst().version());
        }
        @Override public Result calculateQuickResult(ExpressionContext context) { return calculateResult(context); }
        @Override public LookupElement[] calculateLookupItems(ExpressionContext context) {
            return versions(context).stream().map(version -> LookupElementBuilder.create(version.version())).toArray(LookupElement[]::new);
        }
    }
    private HelmfileBlockCompletion() {}
}
