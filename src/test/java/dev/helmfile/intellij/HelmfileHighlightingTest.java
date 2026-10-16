package dev.helmfile.intellij;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.psi.tree.IElementType;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.*;

public class HelmfileHighlightingTest extends BasePlatformTestCase {
    private SyntaxHighlighter highlighter() { return new HelmfileSyntaxHighlighterFactory().getSyntaxHighlighter(getProject(), null); }
    private Map<String, IElementType> tokens(String source) {
        Lexer lexer = highlighter().getHighlightingLexer(); lexer.start(source);
        Map<String, IElementType> result = new HashMap<>();
        int offset = 0;
        while (lexer.getTokenType() != null) {
            assertEquals(offset, lexer.getTokenStart());
            result.put(source.substring(lexer.getTokenStart(), lexer.getTokenEnd()), lexer.getTokenType());
            offset = lexer.getTokenEnd(); lexer.advance();
        }
        assertEquals(source.length(), offset);
        return result;
    }
    public void testFunctionFamiliesKeywordsContextsAndVariablesHaveDistinctTokens() {
        var tokens = tokens("{{ if true }}{{ $tag := requiredEnv \"TAG\" | default (printf \"%s\" .StateValues.versions.api) }}{{ end }}");
        assertSame(HelmfileTokens.KEYWORD, tokens.get("if"));
        assertSame(HelmfileTokens.CONSTANT, tokens.get("true"));
        assertSame(HelmfileTokens.VARIABLE, tokens.get("$tag"));
        assertSame(HelmfileTokens.HELMFILE_FUNCTION, tokens.get("requiredEnv"));
        assertSame(HelmfileTokens.SPRIG_FUNCTION, tokens.get("default"));
        assertSame(HelmfileTokens.GO_FUNCTION, tokens.get("printf"));
        assertSame(HelmfileTokens.CONTEXT, tokens.get(".StateValues"));
        assertSame(HelmfileTokens.PROPERTY, tokens.get("versions"));
        assertFalse(Arrays.equals(highlighter().getTokenHighlights(HelmfileTokens.KEYWORD), highlighter().getTokenHighlights(HelmfileTokens.GO_FUNCTION)));
    }
    public void testEscapedReleaseTemplatesAreHighlightedAsCode() {
        var tokens = tokens("version: '{{`{{ $parts := splitList \"-\" .Release.Name }}{{ range $part := rest $parts }}{{ printf \"%s\" ($part | title) }}{{ end }}`}}'");
        assertSame(HelmfileTokens.SPRIG_FUNCTION, tokens.get("splitList"));
        assertSame(HelmfileTokens.KEYWORD, tokens.get("range"));
        assertSame(HelmfileTokens.GO_FUNCTION, tokens.get("printf"));
        assertSame(HelmfileTokens.CONTEXT, tokens.get(".Release"));
        assertSame(HelmfileTokens.VARIABLE, tokens.get("$part"));
    }
    public void testFunctionWordsInNormalStringsRemainStrings() {
        var tokens = tokens("{{ printf \"requiredEnv default if\" }}");
        assertSame(HelmfileTokens.STRING, tokens.get("\"requiredEnv default if\""));
    }
    public void testLightAndDarkColorsInheritTheActiveThemeAndSettings() {
        for (String name : List.of("Default", "Darcula")) {
            var scheme = EditorColorsManager.getInstance().getScheme(name);
            assertNotNull(scheme);
            for (var key : List.of(HelmfileColors.GO_FUNCTION, HelmfileColors.SPRIG_FUNCTION, HelmfileColors.HELMFILE_FUNCTION))
                assertEquals(scheme.getAttributes(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.FUNCTION_CALL), scheme.getAttributes(key));
            assertEquals(scheme.getAttributes(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.KEYWORD), scheme.getAttributes(HelmfileColors.KEYWORD));
            assertEquals(scheme.getAttributes(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.BRACES), scheme.getAttributes(HelmfileColors.DELIMITERS));
            assertEquals(scheme.getAttributes(com.intellij.openapi.editor.DefaultLanguageHighlighterColors.INSTANCE_FIELD), scheme.getAttributes(HelmfileColors.CONTEXT));
        }
        assertEquals("Helmfile", new HelmfileColorSettingsPage().getDisplayName());
        assertEquals(13, new HelmfileColorSettingsPage().getAttributeDescriptors().length);
    }
    public void testDocumentationWorksInsideEscapedTemplate() {
        var file = myFixture.configureByText("helmfile.yaml.gotmpl", "version: '{{`{{ pri<caret>ntf \"%s\" .Release.Name }}`}}'");
        assertEquals("printf", HelmfileDocumentationProvider.functionAt(file, myFixture.getCaretOffset()).name());
    }
    public void testYamlStringsRetainColorAcrossTemplateActions() {
        var tokens = tokens("name: \"prefix-{{ .StateValues.name }}-suffix\"\nversion: {{ .Values.version }}\nnamespace: production\n");
        var yamlString = tokens.get("\"prefix-");
        assertNotNull(yamlString);
        assertSame(yamlString, tokens.get("-suffix\""));
        assertSame(HelmfileTokens.CONTEXT, tokens.get(".StateValues"));
        assertSame(HelmfileTokens.PROPERTY, tokens.get("name"));
    }
    public void testHighlightingRestartsInsideEscapedTemplatesWithoutLosingTokens() {
        String source = "version: '{{`{{ printf \"%s\" .Release.Name | lower }}`}}'\n";
        Lexer lexer = highlighter().getHighlightingLexer(); lexer.start(source);
        while (lexer.getTokenType() != null) {
            Lexer restarted = highlighter().getHighlightingLexer(); restarted.start(source, lexer.getTokenStart(), source.length(), lexer.getState());
            assertEquals(lexer.getTokenType(), restarted.getTokenType());
            assertEquals(lexer.getTokenEnd(), restarted.getTokenEnd());
            lexer.advance();
        }
    }
}
