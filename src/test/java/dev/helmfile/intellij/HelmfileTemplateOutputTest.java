package dev.helmfile.intellij;

import junit.framework.TestCase;

public class HelmfileTemplateOutputTest extends TestCase {
    static final String MANIFESTS = """
            ---
            # Source: api/templates/config.yaml
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: api
              namespace: staging
            data:
              message: "Hello 🌍"
              script: |
                ---
                untouched
            ---
            # Source: api/templates/role.yaml
            apiVersion: rbac.authorization.k8s.io/v1
            kind: ClusterRole
            metadata:
              name: permissions
            rules: []
            """;
    public void testResourcesKeepRawYamlSourceCommentsAndUnicode() {
        var result = HelmfileTemplateOutput.parse(MANIFESTS);
        assertEquals(MANIFESTS, result.text());
        assertFalse(result.unrecognized());
        assertEquals(2, result.resources().size());
        assertEquals("staging/ConfigMap/api.yaml", result.resources().getFirst().path());
        assertEquals("_no-namespace/ClusterRole/permissions.yaml", result.resources().getLast().path());
        assertTrue(result.resources().getFirst().text().contains("# Source: api/templates/config.yaml\n"));
        assertTrue(result.resources().getFirst().text().contains("message: \"Hello 🌍\"\n"));
        assertTrue(result.resources().getFirst().text().contains("    ---\n    untouched\n"));
    }
    public void testDuplicateResourcesAndGeneratedNamesGetDistinctSafePaths() {
        var result = HelmfileTemplateOutput.parse(MANIFESTS + MANIFESTS + "---\napiVersion: v1\nkind: Job\nmetadata:\n  generateName: migrate-\n");
        assertEquals(5, result.resources().size());
        assertEquals("staging/ConfigMap/api (2).yaml", result.resources().get(2).path());
        assertEquals("_no-namespace/Job/migrate-.yaml", result.resources().getLast().path());
        var unsafe = HelmfileTemplateOutput.parse("apiVersion: v1\nkind: Pod\nmetadata: {name: '../pod', namespace: '..'}\n");
        assertEquals("_/Pod/.._pod.yaml", unsafe.resources().getFirst().path());
    }
    public void testEmptyAndNonManifestDocumentsAndMalformedYamlDoNotHideOtherResources() {
        var empty = HelmfileTemplateOutput.parse("# no resources\n---\n# still empty\n");
        assertTrue(empty.resources().isEmpty());
        assertFalse(empty.unrecognized());
        var result = HelmfileTemplateOutput.parse("hook output\n---\nsecret: [invalid\n" + MANIFESTS);
        assertTrue(result.unrecognized());
        assertEquals(2, result.resources().size());
        assertTrue(result.text().contains("secret: [invalid"));
    }
    public void testYamlAliasesAreInspectedWithoutConstructingObjects() {
        var result = HelmfileTemplateOutput.parse("""
                apiVersion: v1
                kind: ConfigMap
                original: &identity {name: alias, namespace: staging}
                metadata: *identity
                data: !!custom {key: value}
                """);
        assertFalse(result.unrecognized());
        assertEquals("staging/ConfigMap/alias.yaml", result.resources().getFirst().path());
    }
}
