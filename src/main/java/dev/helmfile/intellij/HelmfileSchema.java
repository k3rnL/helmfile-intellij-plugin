package dev.helmfile.intellij;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.*;
import org.jetbrains.yaml.YAMLFileType;
import org.jetbrains.yaml.psi.*;

final class HelmfileSchema {
    private static final String CHART_DEPENDENCIES_REFERENCE = "https://helmfile.readthedocs.io/en/latest/advanced-features/#adding-dependencies-without-forking-the-chart";
    record Field(String name, String type, String description, String reference) {
        String html() { return "<div class='definition'><pre>" + name + ": " + type + "</pre></div><div class='content'>"
                + FunctionCatalog.escape(description) + "<p><a href='" + reference + "'>Helmfile reference</a></p></div>"; }
    }
    private static final Map<String, List<Field>> SCHEMA = new HashMap<>();
    static {
        fields("", """
                repositories|list|Chart repositories available to releases.
                releases|list|Helm releases managed by this state file.
                environments|map|Named environments and their state values.
                values|list|State values files or inline maps exposed through .Values.
                bases|list|State files merged before this state file, in order.
                helmfiles|list|Nested Helmfile states or path specifications.
                helmDefaults|map|Default Helm options inherited by releases.
                templates|map|Reusable release specifications, usually referenced with YAML anchors or inherit.
                commonLabels|map|Labels applied to every release.
                missingFileHandler|string|How to handle missing referenced files: Error, Warn, Info, or Debug.
                kubeContext|string|Kubernetes context used for this state.
                namespace|string|Default release namespace.
                apiVersions|list|Kubernetes API versions used during templating.
                kubeVersion|string|Kubernetes version used during templating.
                lockFilePath|string|Custom path for the Helmfile lock file.
                """);
        fields("releases", """
                name|string|Helm release name.
                chart|string|Repository/chart name, local chart directory, or OCI URL.
                version|string|Chart version or version constraint.
                namespace|string|Kubernetes namespace for the release.
                kubeContext|string|Kubernetes context for the release.
                values|list|Chart values passed to Helm; these do not define Helmfile's .Values.
                secrets|list|Encrypted chart values files decrypted by helm-secrets.
                set|list|Individual Helm values with name and value fields.
                setString|list|Individual Helm values forced to strings.
                setFile|list|Individual Helm values read from files.
                labels|map|Release labels used by Helmfile selectors.
                installed|bool|Whether the release should be installed.
                condition|string|Boolean state values path controlling the release.
                dependencies|list|Additional chart dependencies added to this release's chart without forking it; each entry supports chart, version, and alias.
                needs|list|Release dependencies, optionally qualified by context and namespace.
                hooks|list|Commands executed on Helmfile lifecycle events.
                inherit|list|Release templates to inherit.
                missingFileHandler|string|Behavior for missing values files.
                createNamespace|bool|Create the namespace if it does not exist.
                wait|bool|Wait for Kubernetes resources to become ready.
                waitForJobs|bool|Wait for Jobs when wait is enabled.
                timeout|int|Helm operation timeout in seconds.
                atomic|bool|Roll back a failed upgrade; implies wait.
                cleanupOnFail|bool|Delete newly created resources on upgrade failure.
                force|bool|Use replacement when upgrading resources.
                verify|bool|Verify the chart before installation.
                devel|bool|Allow development chart versions.
                skipDeps|bool|Skip chart dependency updates.
                skipDiffValidation|bool|Skip validation during diff.
                disableValidation|bool|Disable validation during diff.
                disableOpenAPIValidation|bool|Disable OpenAPI schema validation.
                historyMax|int|Maximum number of release revisions to retain.
                reuseValues|bool|Reuse values from the previous release.
                resetValues|bool|Reset release values to chart defaults on upgrade.
                transformers|list|Kustomize transformers applied to rendered manifests.
                strategicMergePatches|list|Strategic merge patches applied to manifests.
                jsonPatches|list|JSON patches applied to manifests.
                """);
        // Helmfile's Dependency struct (pkg/state/helmx.go), rather than Helm's Chart.yaml schema.
        fields("dependencies", """
                chart|string|Dependency chart: repository/chart, local chart path, OCI reference, or go-getter URL.
                version|string|Version or version constraint for the dependency chart.
                alias|string|Alternative name for this chart dependency; allows multiple instances of the same chart.
                """, CHART_DEPENDENCIES_REFERENCE);
        fields("repositories", """
                name|string|Repository alias used in chart names.
                url|string|Helm repository URL.
                oci|bool|Treat this repository as an OCI registry.
                username|string|Repository authentication username.
                password|string|Repository authentication password.
                registryConfig|string|Path to Helm registry authentication configuration.
                managed|string|How Helmfile manages this repository.
                certFile|string|TLS client certificate file.
                keyFile|string|TLS client key file.
                caFile|string|CA certificate file.
                skipTLSVerify|bool|Skip TLS certificate verification.
                passCredentials|bool|Forward repository credentials to other domains.
                plainHttp|bool|Connect to the repository or OCI registry over plain HTTP.
                verify|bool|Verify chart provenance from this repository.
                keyring|string|Keyring used to verify chart provenance.
                """);
        fields("environment", """
                values|list|YAML, templated YAML, HCL files, or inline state values for this environment.
                secrets|list|Encrypted state values files.
                kubeContext|string|Kubernetes context for the environment.
                missingFileHandler|string|Behavior for missing environment files.
                missingFileHandlerConfig|map|Additional missing-file behavior.
                """);
        fields("hooks", """
                events|list|Lifecycle events such as prepare, presync, postsync, or cleanup.
                command|string|Executable to run.
                args|list|Command arguments, which may contain templates.
                showlogs|bool|Show hook command output.
                """);
        fields("set", """
                name|string|Dotted Helm values key.
                value|any|Value assigned to the Helm key.
                file|string|File whose content supplies this Helm value.
                values|list|Array value assigned to the Helm key.
                """);
        fields("helmfiles", """
                path|string|Nested state file path or glob.
                selectors|list|Release selectors forwarded to the nested state.
                selectorsInherited|bool|Inherit parent release selectors.
                values|list|Additional state values passed to the nested state.
                """);
        fields("inherit", """
                template|string|Name of a release template to inherit.
                except|list|Fields excluded from inheritance.
                """);
        SCHEMA.put("helmDefaults", SCHEMA.get("releases").stream().filter(f -> Set.of("kubeContext", "createNamespace", "wait", "waitForJobs", "timeout", "atomic", "cleanupOnFail", "force", "verify", "devel", "skipDeps", "historyMax", "reuseValues", "resetValues", "disableOpenAPIValidation").contains(f.name)).toList());
    }
    private static void fields(String context, String rows) {
        fields(context, rows, "https://helmfile.readthedocs.io/en/stable/configuration/");
    }
    private static void fields(String context, String rows, String reference) {
        SCHEMA.put(context, rows.strip().lines().map(row -> row.split("\\|", 3))
                .map(c -> new Field(c[0], c[1], c[2], c[0].equals("dependencies") ? CHART_DEPENDENCIES_REFERENCE : reference)).toList());
    }
    static String context(List<String> path) {
        if (path.isEmpty()) return "";
        String last = path.getLast();
        if (path.size() == 2 && path.getFirst().equals("environments")) return "environment";
        if (path.size() == 2 && path.getFirst().equals("templates")) return "releases";
        if (last.equals("dependencies") && (path.equals(List.of("releases", "dependencies"))
                || path.size() == 3 && path.getFirst().equals("templates"))) return "dependencies";
        if (path.equals(List.of("releases")) || path.equals(List.of("repositories")) || path.equals(List.of("helmDefaults")) || path.equals(List.of("helmfiles"))) return last;
        if (path.size() == 2 && path.getFirst().equals("releases")) {
            if (Set.of("hooks", "inherit").contains(last)) return last;
            if (Set.of("set", "setString", "setFile").contains(last)) return "set";
        }
        return "unknown";
    }
    static List<Field> fields(List<String> path) { return SCHEMA.getOrDefault(context(path), List.of()); }
    static List<String> ancestors(PsiElement element) {
        List<String> result = new ArrayList<>();
        for (PsiElement parent = element.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof YAMLKeyValue key) result.add(key.getKeyText());
        }
        Collections.reverse(result);
        return result;
    }
    static List<String> completionPath(PsiFile file, int offset) {
        String text = file.getViewProvider().getContents().toString();
        int line = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        String prefix = text.substring(line, offset);
        // Only complete mapping keys; a colon or a YAML comment means a value/comment position.
        if (!prefix.matches("[ \\t]*(?:-\\s*)?[A-Za-z_]*")) return null;
        String indentation = prefix.replaceFirst("[A-Za-z_]*$", "");
        String source = TemplateScanner.mask(text.substring(0, line)) + indentation + "__helmfile_completion__: null\n";
        PsiFile yaml = PsiFileFactory.getInstance(file.getProject()).createFileFromText("completion.yaml", YAMLFileType.YML, source);
        return PsiTreeUtil.findChildrenOfType(yaml, YAMLKeyValue.class).stream()
                .filter(k -> k.getKeyText().equals("__helmfile_completion__")).findFirst().map(HelmfileSchema::ancestors).orElse(null);
    }
    private HelmfileSchema() {}
}
