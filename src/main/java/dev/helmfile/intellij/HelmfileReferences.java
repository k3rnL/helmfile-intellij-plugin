package dev.helmfile.intellij;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import java.util.*;
import java.util.regex.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLScalar;
import org.jetbrains.yaml.psi.YAMLKeyValue;

final class HelmfileReferences {
    private static final Pattern ROOT = Pattern.compile("^\\$?\\.(?:Values|StateValues|Environment\\.Values)\\.");
    static String root(String text) {
        Matcher matcher = ROOT.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }
    static PsiReference[] forAtom(PsiElement atom) {
        String text = atom.getText();
        String root = root(text);
        List<PsiReference> references = new ArrayList<>();
        if (root != null) {
            addPathReferences(atom, text, 0, references);
        } else if (text.startsWith("`") && text.endsWith("`") && text.contains("{{")) {
            nestedReferences(atom, unquote(text), 1, references, 0);
        } else if (text.startsWith("\"") || text.startsWith("`")) {
            String prefix = atom.getContainingFile().getText().substring(0, atom.getTextOffset());
            if (prefix.matches("(?s).*\\b(?:readFile|isFile|isDir)\\s+$")) {
                references.add(new PathReference(atom, stringRange(text), unquote(text)));
            } else {
                Matcher index = Pattern.compile("(?s).*\\bindex\\s+\\$?\\.(?:Values|StateValues|Environment\\.Values)((?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s+((?:\"[^\"]*\"\\s+)*)$").matcher(prefix);
                if (index.matches()) {
                    List<String> path = new ArrayList<>();
                    if (!index.group(1).isEmpty()) path.addAll(Arrays.asList(index.group(1).substring(1).split("\\.")));
                    Matcher keys = Pattern.compile("\"([^\"]*)\"").matcher(index.group(2));
                    while (keys.find()) path.add(keys.group(1));
                    path.add(unquote(text));
                    references.add(new ValueReference(atom, stringRange(text), path));
                }
            }
        }
        return references.toArray(PsiReference.EMPTY_ARRAY);
    }
    private static void addPathReferences(PsiElement atom, String text, int shift, List<PsiReference> references) {
        String root = root(text);
        if (root == null) return;
        List<String> path = new ArrayList<>();
        int offset = root.length();
        for (String part : text.substring(offset).split("\\.")) {
            if (part.isEmpty()) break;
            path.add(part);
            references.add(new ValueReference(atom, new TextRange(shift + offset, shift + offset + part.length()), path));
            offset += part.length() + 1;
        }
    }
    private static void nestedReferences(PsiElement atom, String text, int shift, List<PsiReference> references, int depth) {
        if (depth > 5) return;
        HelmfileLexer lexer = new HelmfileLexer(); lexer.start(text);
        while (lexer.getTokenType() != null) {
            String token = text.substring(lexer.getTokenStart(), lexer.getTokenEnd());
            if (lexer.getTokenType() == HelmfileTokens.PATH) addPathReferences(atom, token, shift + lexer.getTokenStart(), references);
            else if (lexer.getTokenType() == HelmfileTokens.STRING && token.startsWith("`") && token.endsWith("`") && token.contains("{{")) {
                nestedReferences(atom, unquote(token), shift + lexer.getTokenStart() + 1, references, depth + 1);
            }
            lexer.advance();
        }
    }
    static TextRange stringRange(String text) { return new TextRange(1, Math.max(1, text.length() - 1)); }
    static String unquote(String text) { return text.length() >= 2 ? text.substring(1, text.length() - 1) : ""; }
    static final class ValueReference extends PsiPolyVariantReferenceBase<PsiElement> {
        final List<String> path;
        ValueReference(PsiElement element, TextRange range, List<String> path) { super(element, range, true); this.path = List.copyOf(path); }
        @Override public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            return definitions().stream().map(HelmfileValues.Definition::target).distinct()
                    .map(PsiElementResolveResult::new).toArray(ResolveResult[]::new);
        }
        List<HelmfileValues.Definition> definitions() {
            String name = HelmfileValues.key(path);
            return HelmfileValues.model(myElement.getContainingFile()).completionDefinitions().stream().filter(d -> d.path().equals(name)).toList();
        }
        HelmfileValues.Definition definition() {
            List<HelmfileValues.Definition> definitions = definitions();
            return definitions.size() == 1 ? definitions.getFirst() : null;
        }
        @Override public boolean isReferenceTo(@NotNull PsiElement element) {
            YAMLKeyValue declaration = HelmfileSymbols.key(element);
            return declaration != null && definitions().stream().anyMatch(d -> getElement().getManager().areElementsEquivalent(d.key(), declaration));
        }
        @Override public Object @NotNull [] getVariants() { return EMPTY_ARRAY; }
    }
    static final class PathReference extends PsiPolyVariantReferenceBase<PsiElement> {
        private final String path;
        PathReference(PsiElement element, TextRange range, String path) { super(element, range, true); this.path = path; }
        @Override public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
            PsiFile source = myElement.getContainingFile();
            String environment = HelmfileSettings.get(source.getProject()).environment();
            return HelmfileValues.files(source, path, environment).stream().map(f -> PsiManager.getInstance(source.getProject()).findFile(f))
                    .filter(Objects::nonNull).map(PsiElementResolveResult::new).toArray(ResolveResult[]::new);
        }
    }
    static PsiReference[] forScalar(YAMLScalar scalar) {
        if (HelmfileTemplates.isInheritance(scalar) && !HelmfileTemplates.declarations(scalar.getContainingFile()).isEmpty()) {
            return new PsiReference[]{new HelmfileTemplates.TemplateReference(scalar)};
        }
        if (!HelmfileValues.supported(scalar.getContainingFile())) return PsiReference.EMPTY_ARRAY;
        List<String> parents = HelmfileSchema.ancestors(scalar);
        if (parents.isEmpty()) return PsiReference.EMPTY_ARRAY;
        String last = parents.getLast();
        if (!Set.of("values", "secrets", "bases", "helmfiles", "path", "chart").contains(last)) return PsiReference.EMPTY_ARRAY;
        // Inline values may have arbitrary nested keys named 'values' or 'path'.
        boolean fileEntry = parents.size() == 1 && Set.of("values", "bases", "helmfiles").contains(last)
                || parents.size() == 3 && parents.getFirst().equals("environments") && Set.of("values", "secrets").contains(last)
                || parents.size() == 2 && parents.getFirst().equals("releases") && Set.of("values", "secrets", "chart").contains(last)
                || parents.equals(List.of("helmfiles", "path"));
        if (!fileEntry) return PsiReference.EMPTY_ARRAY;
        String raw = scalar.getText(), value = HelmfileValues.pathText(scalar);
        int start = raw.startsWith("\"") || raw.startsWith("'") ? 1 : 0;
        int end = start == 1 ? Math.max(1, raw.length() - 1) : raw.length();
        return new PsiReference[]{new PathReference(scalar, new TextRange(start, end), value)};
    }
    private HelmfileReferences() {}
}
