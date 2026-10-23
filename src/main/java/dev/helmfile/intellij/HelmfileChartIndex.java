package dev.helmfile.intellij;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.util.text.VersionComparatorUtil;
import java.io.IOException;
import java.util.*;
import org.snakeyaml.engine.v2.api.*;

record HelmfileChartIndex(Map<String, List<HelmfileChartIndex.Chart>> charts) {
    record Chart(String name, String version, String description, boolean deprecated) {}
    static HelmfileChartIndex parse(String yaml) throws IOException {
        try {
            Object loaded = new Load(LoadSettings.builder().setCodePointLimit(32 * 1024 * 1024)
                    .setMaxAliasesForCollections(50).build()).loadFromString(yaml);
            if (!(loaded instanceof Map<?, ?> root) || !"v1".equals(root.get("apiVersion"))
                    || !(root.get("entries") instanceof Map<?, ?> entries)) throw new IOException("Not a Helm v1 repository index");
            Map<String, List<Chart>> charts = new TreeMap<>();
            for (var entry : entries.entrySet()) {
                ProgressManager.checkCanceled();
                if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof List<?> versions)) throw new IOException("Invalid chart entries in repository index");
                Map<String, Chart> unique = new LinkedHashMap<>();
                for (Object item : versions) {
                    if (!(item instanceof Map<?, ?> chart) || !(chart.get("version") instanceof String)) throw new IOException("Invalid chart version in repository index");
                    String version = chart.get("version").toString();
                    Object description = chart.get("description");
                    unique.putIfAbsent(version, new Chart(name, version, description == null ? "" : description.toString().replaceAll("\\s+", " "),
                            Boolean.TRUE.equals(chart.get("deprecated"))));
                }
                List<Chart> sorted = new ArrayList<>(unique.values());
                sorted.sort((left, right) -> VersionComparatorUtil.compare(right.version(), left.version()));
                if (!sorted.isEmpty()) charts.put(name, List.copyOf(sorted));
            }
            return new HelmfileChartIndex(Collections.unmodifiableMap(charts));
        } catch (org.snakeyaml.engine.v2.exceptions.YamlEngineException | ClassCastException error) {
            throw new IOException("Invalid Helm repository index", error);
        }
    }
    boolean containsVersion(String chart, String version) {
        return charts.getOrDefault(chart, List.of()).stream().anyMatch(candidate -> normalized(candidate.version()).equals(normalized(version)));
    }
    private static String normalized(String version) { return version.replaceFirst("^v", "").replaceFirst("\\+.*$", ""); }
}
