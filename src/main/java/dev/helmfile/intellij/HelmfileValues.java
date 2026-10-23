package dev.helmfile.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import com.intellij.psi.util.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.jetbrains.yaml.YAMLLanguage;
import org.jetbrains.yaml.psi.*;

/** Static source resolution. No template functions, commands, or secret providers are executed. */
final class HelmfileValues {
    static final String SEPARATOR = "\u001f";
    static String key(List<String> segments) { return String.join(SEPARATOR, segments); }
    static String pathText(YAMLScalar scalar) {
        String raw = scalar.getText().trim();
        if (!raw.contains("{{")) return scalar.getTextValue();
        if (raw.length() >= 2 && (raw.startsWith("\"") && raw.endsWith("\"") || raw.startsWith("'") && raw.endsWith("'"))) return raw.substring(1, raw.length() - 1);
        return raw;
    }
    record Definition(String path, YAMLKeyValue key, String origin, String environment) {
        String sourceLabel() { return environment == null ? origin : origin + " · " + environment; }
        PsiElement target() { return key.getKey() == null ? key : key.getKey(); }
        String preview() {
            YAMLValue value = key.getValue();
            return value instanceof YAMLScalar scalar ? scalar.getTextValue() : value instanceof YAMLSequence ? "list" : "map";
        }
    }
    record Model(PsiFile state, Map<String, Definition> definitions, List<Definition> completionDefinitions,
                 Set<String> environments, List<String> skipped) {}
    private static final Key<CachedValue<Model>> MODEL = Key.create("helmfile.values.model");
    private static final Key<CachedValue<List<Definition>>> ALL_DEFINITIONS = Key.create("helmfile.values.all.environments");
    private static final List<String> STATE_NAMES = List.of("helmfile.yaml.gotmpl", "helmfile.yml.gotmpl", "helmfile.yaml", "helmfile.yml");
    private static final Pattern ENV_NAME = Pattern.compile("\\{\\{-?\\s*\\.Environment\\.Name\\s*-?}}");
    static boolean supported(PsiFile file) { return file != null && file.getViewProvider().getBaseLanguage() == HelmfileLanguage.INSTANCE; }
    static PsiFile original(PsiFile file) {
        PsiFile original = file.getOriginalFile();
        PsiFile base = original.getViewProvider().getPsi(original.getViewProvider().getBaseLanguage());
        return base == null ? original : base.getOriginalFile();
    }
    static YAMLFile yaml(PsiFile file) {
        PsiFile yaml = file.getViewProvider().getPsi(YAMLLanguage.INSTANCE);
        return yaml instanceof YAMLFile f ? f : null;
    }
    static Model model(PsiFile file) {
        PsiFile original = original(file);
        return CachedValuesManager.getManager(file.getProject()).getCachedValue(original, MODEL, () -> {
            Builder builder = new Builder(original, HelmfileSettings.get(original.getProject()).environment());
            return CachedValueProvider.Result.create(builder.build(), PsiModificationTracker.MODIFICATION_COUNT,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, HelmfileSettings.get(original.getProject()));
        }, false);
    }
    /** Reverse lookup follows the declaration's environment, independently of the editor selection. */
    static List<Definition> allDefinitions(PsiFile file) {
        PsiFile original = original(file);
        return CachedValuesManager.getManager(original.getProject()).getCachedValue(original, ALL_DEFINITIONS, () -> {
            PsiFile state = stateFor(original);
            Set<String> pending = new LinkedHashSet<>(List.of("default", HelmfileSettings.get(original.getProject()).environment()));
            Set<String> visited = new HashSet<>();
            List<Definition> definitions = new ArrayList<>();
            while (!pending.isEmpty()) {
                String environment = pending.iterator().next(); pending.remove(environment);
                if (!visited.add(environment)) continue;
                Builder builder = new Builder(original, environment);
                builder.resolve(state);
                definitions.addAll(builder.values.values());
                pending.addAll(builder.environments.stream().filter(name -> !visited.contains(name)).toList());
            }
            return CachedValueProvider.Result.create(definitions.stream().distinct().toList(), PsiModificationTracker.MODIFICATION_COUNT,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, HelmfileSettings.get(original.getProject()));
        }, false);
    }
    static PsiFile stateFor(PsiFile file) {
        VirtualFile virtual = file.getOriginalFile().getVirtualFile();
        if (virtual == null) return file;
        YAMLFile yaml = yaml(file);
        if (STATE_NAMES.contains(virtual.getName()) || (yaml != null && roots(yaml).stream().anyMatch(root -> root.getKeyValueByKey("releases") != null || root.getKeyValueByKey("bases") != null))) return file;
        for (VirtualFile dir = virtual.getParent(); dir != null; dir = dir.getParent()) {
            for (String name : STATE_NAMES) {
                VirtualFile candidate = dir.findChild(name);
                if (candidate != null) return PsiManager.getInstance(file.getProject()).findFile(candidate);
            }
        }
        return file;
    }
    static List<YAMLMapping> roots(YAMLFile yaml) {
        return yaml.getDocuments().stream().map(YAMLDocument::getTopLevelValue).filter(YAMLMapping.class::isInstance).map(YAMLMapping.class::cast).toList();
    }
    static List<VirtualFile> files(PsiFile source, String path, String environment) {
        VirtualFile virtual = source.getOriginalFile().getVirtualFile();
        if (virtual == null || virtual.getParent() == null) return List.of();
        path = ENV_NAME.matcher(path).replaceAll(java.util.regex.Matcher.quoteReplacement(environment));
        if (path.contains("{{") || path.contains("://") || path.startsWith("ref+")) return List.of();
        VirtualFile base = virtual.getParent();
        if (!path.matches(".*[?*\\[\\{].*")) {
            VirtualFile target = path.startsWith("/") ? LocalFileSystem.getInstance().findFileByPath(path) : base.findFileByRelativePath(path);
            return target == null || target.isDirectory() ? List.of() : List.of(target);
        }
        int wildcard = 0;
        while (wildcard < path.length() && "*?[{".indexOf(path.charAt(wildcard)) < 0) wildcard++;
        int slash = path.lastIndexOf('/', wildcard);
        String directory = slash >= 0 ? path.substring(0, slash + 1) : "";
        VirtualFile directoryFile = directory.startsWith("/") ? LocalFileSystem.getInstance().findFileByPath(directory) : base.findFileByRelativePath(directory);
        if (directoryFile == null) return List.of();
        try {
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + path.substring(slash + 1));
            List<VirtualFile> result = new ArrayList<>();
            collectGlob(directoryFile, "", matcher, result, new int[]{0});
            result.sort(Comparator.comparing(VirtualFile::getPath));
            return result;
        } catch (IllegalArgumentException ignored) { return List.of(); }
    }
    private static void collectGlob(VirtualFile directory, String prefix, java.nio.file.PathMatcher matcher, List<VirtualFile> result, int[] visited) {
        for (VirtualFile child : directory.getChildren()) {
            ProgressManager.checkCanceled();
            if (++visited[0] > 5000) return;
            String relative = prefix + child.getName();
            if (child.isDirectory()) { if (!child.is(VFileProperty.SYMLINK)) collectGlob(child, relative + "/", matcher, result, visited); }
            else if (matcher.matches(Path.of(relative))) result.add(child);
        }
    }
    private static final class Builder {
        private final PsiFile context;
        private final String environment;
        private final Map<String, Definition> values = new LinkedHashMap<>();
        private final Set<String> environments = new TreeSet<>();
        private final List<String> skipped = new ArrayList<>();
        private final Set<String> visiting = new HashSet<>();
        Builder(PsiFile context, String environment) { this.context = context; this.environment = environment; }
        Model build() {
            PsiFile state = stateFor(context);
            resolve(state);
            List<Definition> completions = new ArrayList<>(values.values());
            // Files without the current environment still need discoverable environment keys.
            // Resolve each environment separately so their overrides and paths never mix.
            if (!environments.contains(environment)) {
                for (String name : environments) {
                    Builder candidate = new Builder(context, name);
                    candidate.resolve(state);
                    for (Definition definition : candidate.values.values()) {
                        Definition current = values.get(definition.path());
                        if (current != null && current.key().equals(definition.key()) && definition.environment() == null) continue;
                        completions.add(new Definition(definition.path(), definition.key(), definition.origin(), name));
                    }
                }
            }
            Set<String> choices = new TreeSet<>(environments);
            choices.add("default");
            return new Model(state, Collections.unmodifiableMap(values), List.copyOf(completions), Set.copyOf(choices), List.copyOf(skipped));
        }
        private void resolve(PsiFile state) {
            // Collect defaults across all bases first, then apply environment overrides.
            state(state, false, 0);
            state(state, true, 0);
        }
        private void state(PsiFile source, boolean environmentPass, int depth) {
            ProgressManager.checkCanceled();
            if (source == null || depth > 40) return;
            String id = source.getVirtualFile() == null ? source.getName() : source.getVirtualFile().getPath();
            if (!visiting.add(id)) return;
            try {
                YAMLFile yaml = yaml(source);
                if (yaml == null) return;
                for (YAMLMapping root : roots(yaml)) {
                    YAMLKeyValue bases = root.getKeyValueByKey("bases");
                    if (bases != null && bases.getValue() instanceof YAMLSequence seq) {
                        for (YAMLSequenceItem item : seq.getItems()) if (item.getValue() instanceof YAMLScalar scalar) {
                            for (VirtualFile base : files(source, pathText(scalar), environment)) state(PsiManager.getInstance(context.getProject()).findFile(base), environmentPass, depth + 1);
                        }
                    }
                    YAMLKeyValue envs = root.getKeyValueByKey("environments");
                    if (envs != null && envs.getValue() instanceof YAMLMapping mapping) {
                        mapping.getKeyValues().forEach(k -> environments.add(k.getKeyText()));
                        YAMLKeyValue selected = mapping.getKeyValueByKey(environment);
                        if (environmentPass && selected != null && selected.getValue() instanceof YAMLMapping env) entries(source, env.getKeyValueByKey("values"), environment);
                    }
                    if (!environmentPass) entries(source, root.getKeyValueByKey("values"), null);
                }
            } finally { visiting.remove(id); }
        }
        private void entries(PsiFile source, YAMLKeyValue entry, String scope) {
            if (entry == null || !(entry.getValue() instanceof YAMLSequence sequence)) return;
            for (YAMLSequenceItem item : sequence.getItems()) {
                ProgressManager.checkCanceled();
                if (item.getValue() instanceof YAMLMapping inline) merge(inline, "", source.getName(), scope);
                else if (item.getValue() instanceof YAMLScalar scalar) {
                    String path = pathText(scalar);
                    List<VirtualFile> resolved = files(source, path, environment);
                    if (resolved.isEmpty()) { skipped.add(path); continue; }
                    for (VirtualFile virtual : resolved) {
                        PsiFile target = PsiManager.getInstance(context.getProject()).findFile(virtual);
                        if (target == null) continue;
                        YAMLFile yaml = yaml(target);
                        if (yaml == null) { skipped.add(path); continue; }
                        for (YAMLMapping root : roots(yaml)) merge(root, "", virtual.getName(), scope);
                    }
                }
            }
        }
        private void merge(YAMLMapping mapping, String prefix, String origin, String scope) {
            for (YAMLKeyValue key : mapping.getKeyValues()) {
                String path = prefix + key.getKeyText();
                Definition previous = values.get(path);
                boolean map = key.getValue() instanceof YAMLMapping;
                if (!map || previous != null && !(previous.key.getValue() instanceof YAMLMapping)) values.keySet().removeIf(p -> p.startsWith(path + SEPARATOR));
                values.put(path, new Definition(path, key, origin, scope));
                if (key.getValue() instanceof YAMLMapping child) merge(child, path + SEPARATOR, origin, scope);
            }
        }
    }
    private HelmfileValues() {}
}
