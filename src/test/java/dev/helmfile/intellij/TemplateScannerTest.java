package dev.helmfile.intellij;

import org.junit.Test;
import static org.junit.Assert.*;

public class TemplateScannerTest {
    @Test public void delimitersInsideStringsAndCommentsStayInsideAction() {
        for (String source : new String[]{"x: {{ printf \"}}\" }}", "x: {{ `{{ .Values.x }}` }}", "{{/* a }} comment */}}"}) {
            var actions = TemplateScanner.scan(source);
            assertEquals(1, actions.size());
            assertTrue(actions.getFirst().closed());
            assertEquals(source.length(), actions.getFirst().end());
        }
    }
    @Test public void whitespaceTrimmingAndMultilineActions() {
        String source = "a: {{-\n .Values.name\n -}}\nb: {{ .Values.port }}";
        var actions = TemplateScanner.scan(source);
        assertEquals(2, actions.size());
        assertEquals(".Values.name", actions.getFirst().body(source));
        assertEquals(source.length(), TemplateScanner.mask(source).length());
        assertEquals(source.chars().filter(c -> c == '\n').count(), TemplateScanner.mask(source).chars().filter(c -> c == '\n').count());
    }
    @Test public void unfinishedActionIsRetainedForCompletion() {
        String source = "tag: {{ .Values.image.";
        var action = TemplateScanner.at(source, source.length());
        assertNotNull(action);
        assertFalse(action.closed());
    }
    @Test public void escapedQuoteDoesNotEndString() {
        String source = "{{ printf \"\\\"}}\" }}";
        assertEquals(source.length(), TemplateScanner.scan(source).getFirst().end());
    }
    @Test public void lexerCoversEveryCharacterAndCanRestart() {
        String source = "values:\n  - image: {{ .Values.image | quote }}\n{{/* comment */}}";
        HelmfileLexer lexer = new HelmfileLexer(); lexer.start(source);
        int offset = 0;
        while (lexer.getTokenType() != null) {
            assertEquals(offset, lexer.getTokenStart());
            assertTrue(lexer.getTokenEnd() > offset);
            HelmfileLexer restarted = new HelmfileLexer();
            restarted.start(source, offset, source.length(), lexer.getState());
            assertEquals(lexer.getTokenType(), restarted.getTokenType());
            assertEquals(lexer.getTokenEnd(), restarted.getTokenEnd());
            offset = lexer.getTokenEnd(); lexer.advance();
        }
        assertEquals(source.length(), offset);
    }
}
