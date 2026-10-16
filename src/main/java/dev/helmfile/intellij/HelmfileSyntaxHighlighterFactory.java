package dev.helmfile.intellij;

import com.intellij.lexer.*;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.YAMLLanguage;

public final class HelmfileSyntaxHighlighterFactory extends SyntaxHighlighterFactory {
    @Override public @NotNull SyntaxHighlighter getSyntaxHighlighter(Project project, VirtualFile file) {
        SyntaxHighlighter yaml = SyntaxHighlighterFactory.getSyntaxHighlighter(YAMLLanguage.INSTANCE, project, file);
        return new SyntaxHighlighterBase() {
            @Override public @NotNull Lexer getHighlightingLexer() {
                return new HelmfileHighlightingLexer(yaml);
            }
            @Override public TextAttributesKey @NotNull [] getTokenHighlights(IElementType token) {
                if (token == HelmfileTokens.OPEN || token == HelmfileTokens.CLOSE) return pack(HelmfileColors.DELIMITERS);
                if (token == HelmfileTokens.KEYWORD) return pack(HelmfileColors.KEYWORD);
                if (token == HelmfileTokens.IDENTIFIER || token == HelmfileTokens.GO_FUNCTION) return pack(HelmfileColors.GO_FUNCTION);
                if (token == HelmfileTokens.SPRIG_FUNCTION) return pack(HelmfileColors.SPRIG_FUNCTION);
                if (token == HelmfileTokens.HELMFILE_FUNCTION) return pack(HelmfileColors.HELMFILE_FUNCTION);
                if (token == HelmfileTokens.CONTEXT) return pack(HelmfileColors.CONTEXT);
                if (token == HelmfileTokens.PATH || token == HelmfileTokens.PROPERTY) return pack(HelmfileColors.PROPERTY);
                if (token == HelmfileTokens.VARIABLE) return pack(HelmfileColors.VARIABLE);
                if (token == HelmfileTokens.CONSTANT) return pack(HelmfileColors.CONSTANT);
                if (token == HelmfileTokens.STRING) return pack(HelmfileColors.STRING);
                if (token == HelmfileTokens.NUMBER) return pack(HelmfileColors.NUMBER);
                if (token == HelmfileTokens.COMMENT) return pack(HelmfileColors.COMMENT);
                if (token == HelmfileTokens.SYMBOL) return pack(HelmfileColors.OPERATOR);
                return yaml.getTokenHighlights(token);
            }
        };
    }
}
