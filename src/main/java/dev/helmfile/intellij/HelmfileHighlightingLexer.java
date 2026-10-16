package dev.helmfile.intellij;

import com.intellij.lexer.*;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.psi.tree.IElementType;
import java.util.*;
import java.util.regex.*;
import org.jetbrains.annotations.NotNull;

/** Flattens YAML, template actions, and escaped template actions into editor color tokens. */
final class HelmfileHighlightingLexer extends LexerBase {
    private record Token(int start, int end, IElementType type) {}
    private static final Pattern CONTEXT = Pattern.compile("^\\$?\\.(?:Values|StateValues|Environment|Release|Chart|Capabilities|Template)(?=\\.|$)");
    private final SyntaxHighlighter yaml;
    private final List<Token> tokens = new ArrayList<>();
    private CharSequence buffer = "";
    private int index, end;
    HelmfileHighlightingLexer(SyntaxHighlighter yaml) { this.yaml = yaml; }
    @Override public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer; end = endOffset; index = 0; tokens.clear();
        collect(buffer.subSequence(0, endOffset), 0, false, 0);
        while (index < tokens.size() && tokens.get(index).end <= startOffset) index++;
        if (index < tokens.size() && tokens.get(index).start < startOffset) {
            Token token = tokens.get(index); tokens.set(index, new Token(startOffset, token.end, token.type));
        }
    }
    private void collect(CharSequence text, int offset, boolean escaped, int depth) {
        List<Token> yamlTokens = new ArrayList<>();
        if (!escaped) {
            // Keep YAML strings and block scalars in the same lexer state across Go actions.
            Lexer yamlLexer = yaml.getHighlightingLexer(); yamlLexer.start(TemplateScanner.mask(text.toString()));
            while (yamlLexer.getTokenType() != null) {
                yamlTokens.add(new Token(yamlLexer.getTokenStart(), yamlLexer.getTokenEnd(), yamlLexer.getTokenType()));
                yamlLexer.advance();
            }
        }
        int yamlIndex = 0;
        HelmfileLexer lexer = new HelmfileLexer(); lexer.start(text);
        while (lexer.getTokenType() != null) {
            int start = lexer.getTokenStart(), end = lexer.getTokenEnd();
            IElementType type = lexer.getTokenType();
            if (type == HelmfileTokens.DATA && !escaped) {
                while (yamlIndex < yamlTokens.size() && yamlTokens.get(yamlIndex).end <= start) yamlIndex++;
                for (int i = yamlIndex; i < yamlTokens.size() && yamlTokens.get(i).start < end; i++) {
                    Token token = yamlTokens.get(i);
                    add(offset + Math.max(start, token.start), offset + Math.min(end, token.end), token.type);
                }
            } else if (type == HelmfileTokens.STRING && text.charAt(start) == '`' && end > start + 1 && text.charAt(end - 1) == '`'
                    && text.subSequence(start + 1, end - 1).toString().contains("{{") && depth < 5) {
                add(offset + start, offset + start + 1, HelmfileTokens.STRING);
                collect(text.subSequence(start + 1, end - 1), offset + start + 1, true, depth + 1);
                add(offset + end - 1, offset + end, HelmfileTokens.STRING);
            } else if (type == HelmfileTokens.PATH) {
                String path = text.subSequence(start, end).toString();
                Matcher matcher = CONTEXT.matcher(path);
                int consumed = 0;
                if (matcher.find()) { consumed = matcher.end(); add(offset + start, offset + start + consumed, HelmfileTokens.CONTEXT); }
                for (int i = consumed; i < path.length();) {
                    int from = i++;
                    if (path.charAt(from) == '.') add(offset + start + from, offset + start + i, HelmfileTokens.SYMBOL);
                    else {
                        while (i < path.length() && path.charAt(i) != '.') i++;
                        add(offset + start + from, offset + start + i, HelmfileTokens.PROPERTY);
                    }
                }
            } else add(offset + start, offset + end, type == HelmfileTokens.DATA ? HelmfileTokens.STRING : type);
            lexer.advance();
        }
    }
    private void add(int start, int end, IElementType type) { if (end > start) tokens.add(new Token(start, end, type)); }
    @Override public int getState() { return 0; }
    @Override public IElementType getTokenType() { return index < tokens.size() ? tokens.get(index).type : null; }
    @Override public int getTokenStart() { return index < tokens.size() ? tokens.get(index).start : end; }
    @Override public int getTokenEnd() { return index < tokens.size() ? tokens.get(index).end : end; }
    @Override public void advance() { index++; }
    @Override public @NotNull CharSequence getBufferSequence() { return buffer; }
    @Override public int getBufferEnd() { return end; }
}
