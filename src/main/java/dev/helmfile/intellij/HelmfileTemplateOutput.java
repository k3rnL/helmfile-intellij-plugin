package dev.helmfile.intellij;

import com.intellij.openapi.progress.ProgressManager;
import java.util.*;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.*;

/** Inspect resource identities without constructing objects or rewriting rendered YAML. */
final class HelmfileTemplateOutput {
    private static final Pattern SEPARATOR = Pattern.compile("(?m)^---[ \\t]*(?:#.*)?(?:\\r?\\n|$)");
    record Resource(String path, String text) {}
    record Result(String text, List<Resource> resources, boolean unrecognized) {}

    static Result parse(String text) {
        var settings = LoadSettings.builder().setCodePointLimit(HelmfileTemplateRun.MAX_OUTPUT)
                .setMaxAliasesForCollections(50).build();
        List<Resource> resources = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        boolean unrecognized = false;
        for (String document : SEPARATOR.split(text, -1)) {
            ProgressManager.checkCanceled();
            try {
                var node = new Compose(settings).composeString(document);
                if (node.isEmpty()) continue;
                String kind = scalar(field(node.get(), "kind"));
                if (kind.isBlank() || scalar(field(node.get(), "apiVersion")).isBlank()) { unrecognized = true; continue; }
                Node metadata = field(node.get(), "metadata");
                String namespace = scalar(field(metadata, "namespace"));
                String name = scalar(field(metadata, "name"));
                if (name.isBlank()) name = scalar(field(metadata, "generateName"));
                if (name.isBlank()) name = "document-" + (resources.size() + 1);
                String path = component(namespace.isBlank() ? "_no-namespace" : namespace) + "/" + component(kind) + "/" + component(name);
                int occurrence = occurrences.merge(path, 1, Integer::sum);
                resources.add(new Resource(path + (occurrence > 1 ? " (" + occurrence + ")" : "") + ".yaml", document));
            } catch (YamlEngineException error) {
                // Parser messages can include Secret values. Keep diagnostics generic; raw output stays in the viewer/console.
                unrecognized = true;
            }
        }
        return new Result(text, List.copyOf(resources), unrecognized);
    }
    private static Node field(Node node, String key) {
        if (node instanceof MappingNode mapping) for (var entry : mapping.getValue())
            if (entry.getKeyNode() instanceof ScalarNode scalar && key.equals(scalar.getValue())) return entry.getValueNode();
        return null;
    }
    private static String scalar(Node node) { return node instanceof ScalarNode scalar ? scalar.getValue() : ""; }
    private static String component(String text) {
        String value = text.replaceAll("[^A-Za-z0-9._-]", "_");
        return value.equals(".") || value.equals("..") ? "_" : value;
    }
    private HelmfileTemplateOutput() {}
}
