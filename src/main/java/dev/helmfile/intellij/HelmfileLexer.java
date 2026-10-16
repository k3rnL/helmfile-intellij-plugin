package dev.helmfile.intellij;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public final class HelmfileLexer extends LexerBase {
    private record Token(int start, int end, IElementType type, int state) {}
    private final boolean grouped;
    private final List<Token> tokens = new ArrayList<>();
    private CharSequence buffer = "";
    private int index, end;
    public HelmfileLexer() { this(false); }
    HelmfileLexer(boolean grouped) { this.grouped = grouped; }
    @Override public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer; end = endOffset; tokens.clear(); index = 0;
        int cursor = 0;
        for (TemplateScanner.Action action : TemplateScanner.scan(buffer.subSequence(0, endOffset))) {
            add(cursor, action.start(), HelmfileTokens.DATA, 0);
            if (grouped) add(action.start(), action.end(), HelmfileTokens.ACTION, 0);
            else {
                add(action.start(), action.bodyStart(), HelmfileTokens.OPEN, 0);
                lexBody(action.bodyStart(), action.bodyEnd());
                add(action.bodyEnd(), action.end(), HelmfileTokens.CLOSE, 1);
            }
            cursor = action.end();
        }
        add(cursor, endOffset, HelmfileTokens.DATA, 0);
        while (index < tokens.size() && tokens.get(index).end <= startOffset) index++;
        if (index < tokens.size() && tokens.get(index).start < startOffset) {
            Token t = tokens.get(index); tokens.set(index, new Token(startOffset, t.end, t.type, t.state));
        }
    }
    private void add(int start, int end, IElementType type, int state) { if (end > start) tokens.add(new Token(start, end, type, state)); }
    private void lexBody(int start, int end) {
        for (int i = start; i < end;) {
            int s = i++;
            char c = buffer.charAt(s);
            IElementType type;
            if (Character.isWhitespace(c)) {
                type = TokenType.WHITE_SPACE;
                while (i < end && Character.isWhitespace(buffer.charAt(i))) i++;
            } else if (c == '"' || c == '\'' || c == '`') {
                type = HelmfileTokens.STRING;
                while (i < end) { char next = buffer.charAt(i++); if (next == '\\' && c != '`' && i < end) i++; else if (next == c) break; }
            } else if (c == '/' && i < end && buffer.charAt(i) == '*') {
                type = HelmfileTokens.COMMENT;
                i++;
                while (i < end && !(buffer.charAt(i - 1) == '*' && buffer.charAt(i) == '/')) i++;
                if (i < end) i++;
            } else if (c == '.' || c == '$') {
                type = HelmfileTokens.PATH;
                while (i < end && (Character.isJavaIdentifierPart(buffer.charAt(i)) || buffer.charAt(i) == '.')) i++;
                if (c == '$' && (i == s + 1 || buffer.charAt(s + 1) != '.')) type = HelmfileTokens.VARIABLE;
            } else if (Character.isDigit(c) || (c == '-' && i < end && Character.isDigit(buffer.charAt(i)))) {
                type = HelmfileTokens.NUMBER;
                while (i < end && (Character.isLetterOrDigit(buffer.charAt(i)) || buffer.charAt(i) == '.')) i++;
            } else if (Character.isJavaIdentifierStart(c)) {
                type = HelmfileTokens.IDENTIFIER;
                while (i < end && Character.isJavaIdentifierPart(buffer.charAt(i))) i++;
                String name = buffer.subSequence(s, i).toString();
                if (java.util.Set.of("if", "else", "end", "range", "with", "define", "block", "template", "break", "continue").contains(name)) type = HelmfileTokens.KEYWORD;
                else if (java.util.Set.of("true", "false", "nil").contains(name)) type = HelmfileTokens.CONSTANT;
                else {
                    FunctionCatalog.Function function = FunctionCatalog.find(name);
                    if (function != null) type = switch (function.family()) {
                        case "Helmfile" -> HelmfileTokens.HELMFILE_FUNCTION;
                        case "Sprig" -> HelmfileTokens.SPRIG_FUNCTION;
                        default -> HelmfileTokens.GO_FUNCTION;
                    };
                }
            } else type = HelmfileTokens.SYMBOL;
            add(s, i, type, 1);
        }
    }
    @Override public int getState() { return index < tokens.size() ? tokens.get(index).state : 0; }
    @Override public IElementType getTokenType() { return index < tokens.size() ? tokens.get(index).type : null; }
    @Override public int getTokenStart() { return index < tokens.size() ? tokens.get(index).start : end; }
    @Override public int getTokenEnd() { return index < tokens.size() ? tokens.get(index).end : end; }
    @Override public void advance() { index++; }
    @Override public @NotNull CharSequence getBufferSequence() { return buffer; }
    @Override public int getBufferEnd() { return end; }
}
