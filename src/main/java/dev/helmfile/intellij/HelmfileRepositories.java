package dev.helmfile.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import java.util.*;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.*;

/** Repository declarations and immutable download settings, resolved without executing templates. */
final class HelmfileRepositories {
    record Access(String name, String url, boolean oci, String username, String password, String caFile,
                  String certFile, String keyFile, boolean skipTLSVerify, boolean passCredentials, boolean plainHttp) {
        @Override public String toString() { return "Helm repository " + name; }
        boolean downloadable() {
            return !oci && (url.startsWith("https://") || url.startsWith("http://"))
                    && !url.contains("{{") && !username.contains("{{") && !password.contains("{{");
        }
    }
    record Repository(String name, YAMLKeyValue declaration, Access access) {
        PsiElement target() { return declaration.getValue() == null ? declaration.getKey() : declaration.getValue(); }
        String source() { return declaration.getContainingFile().getName(); }
    }
    record Model(List<Repository> entries, boolean incomplete) {
        Map<String, Repository> named() {
            Map<String, Repository> result = new LinkedHashMap<>();
            entries.forEach(repository -> result.put(repository.name(), repository));
            return result;
        }
    }
    private static final Key<CachedValue<Model>> MODEL = Key.create("helmfile.repositories");
    private static final Pattern VALUE = Pattern.compile("\\{\\{-?\\s*(\\$?\\.(?:Values|StateValues|Environment\\.Values)\\.[A-Za-z0-9_.]+|\\.Environment\\.Name)\\s*-?}}");

    static Model model(PsiFile file) {
        PsiFile original = HelmfileValues.original(file);
        return CachedValuesManager.getManager(original.getProject()).getCachedValue(original, MODEL, () -> {
            Builder builder = new Builder();
            builder.collect(HelmfileValues.stateFor(original), new HashSet<>(), 0);
            return CachedValueProvider.Result.create(new Model(List.copyOf(builder.entries), builder.incomplete),
                    PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    HelmfileSettings.get(original.getProject()));
        }, false);
    }
    static boolean isChartField(YAMLKeyValue key, String field) {
        if (!key.getKeyText().equals(field)) return false;
        String context = HelmfileSchema.context(HelmfileSchema.ancestors(key));
        return context.equals("releases") || context.equals("dependencies");
    }
    static String value(YAMLMapping mapping, String key) {
        YAMLKeyValue entry = mapping.getKeyValueByKey(key);
        if (entry == null) return "";
        if (entry.getValue() instanceof YAMLScalar scalar) return HelmfileValues.pathText(scalar);
        // An unquoted Go-only value is an outer-language fragment, not a YAML scalar.
        String text = entry.getContainingFile().getViewProvider().getContents().toString();
        int start = entry.getTextRange().getEndOffset();
        int end = text.indexOf('\n', start);
        String tail = text.substring(start, end < 0 ? text.length() : end).trim();
        return tail.contains("{{") ? tail : "";
    }
    static String resolved(PsiFile file, String value) {
        var matcher = VALUE.matcher(value);
        return matcher.replaceAll(match -> {
            String path = match.group(1);
            String result = null;
            if (path.equals(".Environment.Name")) result = HelmfileSettings.get(file.getProject()).environment();
            else {
                String root = HelmfileReferences.root(path);
                var definition = HelmfileValues.model(file).definitions().get(HelmfileValues.key(Arrays.asList(path.substring(root.length()).split("\\."))));
                if (definition != null && definition.key().getValue() instanceof YAMLScalar scalar) result = HelmfileValues.pathText(scalar);
            }
            return java.util.regex.Matcher.quoteReplacement(result == null ? match.group() : result);
        });
    }
    static String chart(YAMLMapping mapping, Set<YAMLMapping> visiting) {
        if (!visiting.add(mapping) || visiting.size() > 40) return "";
        String chart = value(mapping, "chart");
        if (!chart.isEmpty()) return resolved(mapping.getContainingFile(), chart);
        YAMLKeyValue inherit = mapping.getKeyValueByKey("inherit");
        if (inherit == null) return "";
        for (YAMLScalar scalar : PsiTreeUtil.findChildrenOfType(inherit, YAMLScalar.class)) {
            if (!HelmfileTemplates.isInheritance(scalar)) continue;
            YAMLKeyValue parent = HelmfileTemplates.declarations(mapping.getContainingFile()).get(scalar.getTextValue());
            if (parent != null && parent.getValue() instanceof YAMLMapping template) {
                chart = chart(template, visiting);
                if (!chart.isEmpty()) return chart;
            }
        }
        return "";
    }
    static String alias(PsiFile file, String chart) {
        if (chart.isBlank() || chart.contains("{{") || chart.contains("://") || chart.contains("::")
                || chart.startsWith(".") || chart.startsWith("/") || chart.startsWith("~") || chart.contains("\\")
                || chart.matches("^[A-Za-z]:.*")) return null;
        VirtualFile virtual = file.getOriginalFile().getVirtualFile();
        if (virtual != null && virtual.getParent() != null && virtual.getParent().findFileByRelativePath(chart) != null) return null;
        int slash = chart.indexOf('/');
        return slash > 0 ? chart.substring(0, slash) : null;
    }
    static RepositoryReference reference(YAMLScalar scalar) {
        YAMLKeyValue key = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue.class);
        if (key == null || !isChartField(key, "chart")) return null;
        String chart = HelmfileValues.pathText(scalar);
        String alias = alias(scalar.getContainingFile(), chart);
        if (alias == null) return null;
        int start = scalar.getText().startsWith("\"") || scalar.getText().startsWith("'") ? 1 : 0;
        return new RepositoryReference(scalar, new TextRange(start, start + alias.length()), alias);
    }
    static HelmfileRepositoryService.Query query(PsiFile file, String chart) {
        if (chart.isBlank() || chart.contains("{{")) return null;
        var repositories = model(file).named();
        if (chart.startsWith("oci://")) {
            int slash = chart.lastIndexOf('/');
            if (slash <= "oci://".length() || slash == chart.length() - 1) return null;
            String base = chart.substring(6, slash), name = chart.substring(slash + 1);
            if (name.contains("@") || name.contains(":")) return null;
            Access access = repositories.values().stream().map(Repository::access)
                    .filter(candidate -> candidate.oci() && base.equals(candidate.url().replaceFirst("^(?:oci|https?)://", "").replaceFirst("/+$", "")))
                    .findFirst().orElse(new Access(base, base, true, "", "", "", "", "", false, false, false));
            return new HelmfileRepositoryService.Query(access, name);
        }
        String alias = alias(file, chart);
        Repository repository = alias == null ? null : repositories.get(alias);
        return repository == null ? null : new HelmfileRepositoryService.Query(repository.access(), repository.access().oci() ? chart.substring(alias.length() + 1) : "");
    }
    static final class RepositoryReference extends PsiReferenceBase<YAMLScalar> {
        private final String name;
        RepositoryReference(YAMLScalar scalar, TextRange range, String name) { super(scalar, range, true); this.name = name; }
        @Override public PsiElement resolve() {
            Repository repository = model(myElement.getContainingFile()).named().get(name);
            return repository == null ? null : repository.target();
        }
        @Override public Object @NotNull [] getVariants() { return EMPTY_ARRAY; }
    }
    private static final class Builder {
        final List<Repository> entries = new ArrayList<>();
        boolean incomplete;
        void collect(PsiFile file, Set<PsiFile> visiting, int depth) {
            ProgressManager.checkCanceled();
            if (file == null || depth > 40 || !visiting.add(file)) { incomplete = true; return; }
            try {
                YAMLFile yaml = HelmfileValues.yaml(file);
                if (yaml == null) { incomplete = true; return; }
                for (YAMLMapping root : HelmfileValues.roots(yaml)) {
                    YAMLKeyValue bases = root.getKeyValueByKey("bases");
                    if (bases != null && bases.getValue() instanceof YAMLSequence sequence) {
                        for (YAMLSequenceItem item : sequence.getItems()) if (item.getValue() instanceof YAMLScalar scalar) {
                            var files = HelmfileValues.files(file, HelmfileValues.pathText(scalar), HelmfileSettings.get(file.getProject()).environment());
                            if (files.isEmpty()) incomplete = true;
                            for (VirtualFile base : files) collect(PsiManager.getInstance(file.getProject()).findFile(base), visiting, depth + 1);
                        }
                    }
                    YAMLKeyValue repositories = root.getKeyValueByKey("repositories");
                    if (repositories == null) continue;
                    if (!(repositories.getValue() instanceof YAMLSequence sequence)) { incomplete = true; continue; }
                    for (YAMLSequenceItem item : sequence.getItems()) if (item.getValue() instanceof YAMLMapping mapping) {
                        String name = resolved(file, value(mapping, "name"));
                        YAMLKeyValue declaration = mapping.getKeyValueByKey("name");
                        if (declaration == null || name.isEmpty() || name.contains("{{")) { incomplete = true; continue; }
                        String prefix = name.toUpperCase(Locale.ROOT).replace('-', '_');
                        String username = resolved(file, value(mapping, "username"));
                        String password = resolved(file, value(mapping, "password"));
                        if (username.isEmpty()) username = System.getenv().getOrDefault(prefix + "_USERNAME", "");
                        if (password.isEmpty()) password = System.getenv().getOrDefault(prefix + "_PASSWORD", "");
                        entries.add(new Repository(name, declaration, new Access(name, resolved(file, value(mapping, "url")),
                                value(mapping, "oci").equals("true"), username, password, path(file, mapping, "caFile"),
                                path(file, mapping, "certFile"), path(file, mapping, "keyFile"),
                                value(mapping, "skipTLSVerify").equals("true"), value(mapping, "passCredentials").equals("true"),
                                value(mapping, "plainHttp").equals("true"))));
                    }
                }
            } finally { visiting.remove(file); }
        }
        private String path(PsiFile file, YAMLMapping mapping, String key) {
            String value = resolved(file, value(mapping, key));
            VirtualFile virtual = file.getOriginalFile().getVirtualFile();
            return value.isEmpty() || value.startsWith("/") || virtual == null || virtual.getParent() == null ? value
                    : virtual.getParent().getPath() + "/" + value;
        }
    }
    private HelmfileRepositories() {}
}
