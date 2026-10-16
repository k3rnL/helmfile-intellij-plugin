package dev.helmfile.intellij;

import java.util.ArrayList;
import java.util.List;

/** Recognizes actions without mistaking delimiters inside Go strings or comments for endings. */
public final class TemplateScanner {
    private TemplateScanner() {}
    public record Action(int start, int bodyStart, int bodyEnd, int end, boolean closed) {
        public String body(CharSequence text) { return text.subSequence(bodyStart, bodyEnd).toString().trim(); }
    }
    public static List<Action> scan(CharSequence text) {
        List<Action> actions = new ArrayList<>();
        int length = text.length();
        for (int i = 0; i + 1 < length; i++) {
            if (text.charAt(i) != '{' || text.charAt(i + 1) != '{') continue;
            int start = i;
            int body = i + 2;
            if (body + 1 < length && text.charAt(body) == '-' && Character.isWhitespace(text.charAt(body + 1))) body++;
            int j = body;
            char quote = 0;
            boolean comment = false;
            for (; j < length; j++) {
                char c = text.charAt(j);
                if (comment) {
                    if (c == '*' && j + 1 < length && text.charAt(j + 1) == '/') { comment = false; j++; }
                } else if (quote != 0) {
                    if (c == '\\' && quote != '`') j++;
                    else if (c == quote) quote = 0;
                } else if (c == '"' || c == '\'' || c == '`') quote = c;
                else if (c == '/' && j + 1 < length && text.charAt(j + 1) == '*') { comment = true; j++; }
                else if (c == '}' && j + 1 < length && text.charAt(j + 1) == '}') break;
            }
            boolean closed = j + 1 < length;
            int end = closed ? j + 2 : length;
            int bodyEnd = closed ? j : length;
            if (closed && bodyEnd > body && text.charAt(bodyEnd - 1) == '-' && Character.isWhitespace(text.charAt(bodyEnd - 2))) bodyEnd--;
            actions.add(new Action(start, body, bodyEnd, end, closed));
            i = end - 1;
        }
        return actions;
    }
    public static Action at(CharSequence text, int offset) {
        for (Action action : scan(text)) {
            if (offset >= action.bodyStart && offset <= action.bodyEnd) return action;
        }
        return null;
    }
    /** Equal-length YAML projection used only for incomplete-key completion. */
    public static String mask(String text) {
        char[] result = text.toCharArray();
        for (Action action : scan(text)) {
            for (int i = action.start; i < action.end; i++) if (result[i] != '\n' && result[i] != '\r') result[i] = ' ';
        }
        return new String(result);
    }
}
