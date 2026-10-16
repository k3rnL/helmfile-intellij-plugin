package dev.helmfile.intellij;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

final class HelmfileColors {
    static final TextAttributesKey DELIMITERS = key("DELIMITERS", DefaultLanguageHighlighterColors.BRACES),
            KEYWORD = key("KEYWORD", DefaultLanguageHighlighterColors.KEYWORD),
            GO_FUNCTION = key("GO_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL),
            SPRIG_FUNCTION = key("SPRIG_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL),
            HELMFILE_FUNCTION = key("HELMFILE_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_CALL),
            CONTEXT = key("CONTEXT", DefaultLanguageHighlighterColors.INSTANCE_FIELD),
            PROPERTY = key("PROPERTY", DefaultLanguageHighlighterColors.INSTANCE_FIELD),
            VARIABLE = key("VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE),
            STRING = key("STRING", DefaultLanguageHighlighterColors.STRING),
            NUMBER = key("NUMBER", DefaultLanguageHighlighterColors.NUMBER),
            CONSTANT = key("CONSTANT", DefaultLanguageHighlighterColors.CONSTANT),
            OPERATOR = key("OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN),
            COMMENT = key("COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT);
    // A new key namespace also avoids retaining the bundled palette from version 0.2.0.
    private static TextAttributesKey key(String name, TextAttributesKey fallback) { return TextAttributesKey.createTextAttributesKey("HELMFILE_THEME_" + name, fallback); }
    private HelmfileColors() {}
}
