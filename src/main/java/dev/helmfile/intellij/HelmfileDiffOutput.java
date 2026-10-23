package dev.helmfile.intellij;

import java.util.*;
import java.util.regex.Pattern;

/** The standard helm-diff renderer, with full context. No YAML evaluation or patch application. */
final class HelmfileDiffOutput {
    private static final int MAX_RESOURCES = 5_000;
    enum Status { ADDED, MODIFIED, DELETED, OWNERSHIP, SUPPRESSED }
    record Resource(String release, String namespace, String name, String kind, String api, Status status,
                    String before, String after, boolean partial) {
        String path() { return (release.isEmpty() ? "" : release + "/") + (namespace.isEmpty() ? "_cluster" : namespace) + "/" + kind + "/" + name + ".yaml"; }
        boolean suppressed() { return status == Status.SUPPRESSED; }
    }
    record Result(List<Resource> resources, List<String> unparsed) {
        boolean partial() { return !unparsed.isEmpty() || resources.stream().anyMatch(Resource::partial); }
    }
    private static final Pattern ANSI = Pattern.compile("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\))");
    private static final Pattern HEADER = Pattern.compile("^([^,]*), ([^,]+), (\\S+) \\(([^)]+)\\) (has been added:|has been removed:|has changed:|changed ownership:|has changed, but diff is empty after suppression\\.)$");
    private static final Pattern COMPARISON = Pattern.compile("^Comparing release=([^,]+), chart=.+$");

    static Result parse(String text) {
        List<Resource> resources = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        Builder current = null;
        String release = "";
        for (String line : ANSI.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("").lines().toList()) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled();
            var comparison = COMPARISON.matcher(line);
            if (comparison.matches()) {
                if (current != null) { resources.add(current.build()); current = null; }
                release = comparison.group(1);
                continue;
            }
            var header = HEADER.matcher(line);
            if (header.matches()) {
                if (current != null) resources.add(current.build());
                if (resources.size() >= MAX_RESOURCES) {
                    unparsed.add("More than 5,000 resources; narrow the diff to individual releases.");
                    current = null;
                    break;
                }
                current = new Builder(release, header.group(1), header.group(2), header.group(3), header.group(4), switch (header.group(5)) {
                    case "has been added:" -> Status.ADDED;
                    case "has been removed:" -> Status.DELETED;
                    case "changed ownership:" -> Status.OWNERSHIP;
                    case "has changed, but diff is empty after suppression." -> Status.SUPPRESSED;
                    default -> Status.MODIFIED;
                });
            } else if (current != null && line.equals("...")) {
                current.partial = true;
                current.before.append("# ... context omitted by helm-diff ...\n");
                current.after.append("# ... context omitted by helm-diff ...\n");
            } else if (current != null && line.startsWith("+ Changes suppressed on sensitive content of type ")) {
                current.status = Status.SUPPRESSED;
            } else if (current != null && (line.startsWith("+ ") || line.startsWith("- ") || line.startsWith("  ") || line.isEmpty())) {
                String content = line.isEmpty() ? "" : line.substring(2);
                if (!line.startsWith("+ ")) current.before.append(content).append('\n');
                if (!line.startsWith("- ")) current.after.append(content).append('\n');
            } else if (!line.isBlank()) {
                if (current != null) { resources.add(current.build()); current = null; }
                unparsed.add(line);
            }
        }
        if (current != null) resources.add(current.build());
        return new Result(List.copyOf(resources), List.copyOf(unparsed));
    }
    private static final class Builder {
        final String release, namespace, name, kind, api;
        final StringBuilder before = new StringBuilder(), after = new StringBuilder();
        Status status;
        boolean partial;
        Builder(String release, String namespace, String name, String kind, String api, Status status) {
            this.release = release; this.namespace = namespace; this.name = name; this.kind = kind; this.api = api; this.status = status;
        }
        Resource build() {
            boolean invalid = !suppressed() && before.isEmpty() && after.isEmpty();
            return new Resource(release, namespace, name, kind, api, status,
                    status == Status.ADDED && before.toString().isBlank() ? "" : before.toString(),
                    status == Status.DELETED && after.toString().isBlank() ? "" : after.toString(), partial || invalid);
        }
        boolean suppressed() { return status == Status.SUPPRESSED; }
    }
    private HelmfileDiffOutput() {}
}
