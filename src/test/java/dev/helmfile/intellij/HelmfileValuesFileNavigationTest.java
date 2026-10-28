package dev.helmfile.intellij;

import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.Arrays;

public class HelmfileValuesFileNavigationTest extends BasePlatformTestCase {
    @Override protected void tearDown() throws Exception {
        try { HelmfileSettings.get(getProject()).select("default"); } finally { super.tearDown(); }
    }
    private PsiFile state(String path) {
        return myFixture.configureByText("helmfile.yaml.gotmpl", """
                environments:
                  quality:
                    values:
                      - envPath: k8s/quality
                  production:
                    values:
                      - envPath: k8s/production
                ---
                releases:
                  - name: trino
                    chart: trino/trino
                    values:
                      - %s
                """.formatted(path));
    }
    private PsiElement[] targets(PsiFile file, String name) {
        int offset = file.getText().lastIndexOf(name);
        assertTrue(offset >= 0);
        return new HelmfileGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.getEditor());
    }
    private void assertFile(PsiFile expected, PsiElement[] targets) {
        assertNotNull(targets); assertSize(1, targets);
        assertEquals(expected.getVirtualFile(), targets[0].getContainingFile().getVirtualFile());
    }
    public void testUnquotedStateValuePathOpensTheSelectedEnvironmentsFile() {
        PsiFile quality = myFixture.addFileToProject("k8s/quality/values/databases/trino.yaml", "replicas: 1\n");
        PsiFile production = myFixture.addFileToProject("k8s/production/values/databases/trino.yaml", "replicas: 3\n");
        PsiFile file = state("{{ .StateValues.envPath }}/values/databases/trino.yaml");
        HelmfileSettings.get(getProject()).select("quality");
        assertFile(quality, targets(file, "trino.yaml"));
        HelmfileSettings.get(getProject()).select("production");
        assertFile(production, targets(file, "trino.yaml"));
    }
    public void testQuotedPathsAndEnvironmentNamesResolveTogether() {
        PsiFile expected = myFixture.addFileToProject("k8s/quality/quality/trino.yaml", "enabled: true\n");
        PsiFile file = state("'{{ $.Environment.Values.envPath }}/{{ .Environment.Name }}/trino.yaml'");
        HelmfileSettings.get(getProject()).select("quality");
        assertFile(expected, targets(file, "trino.yaml"));
    }
    public void testLiteralValuesFilesAlsoNavigateFromTheFilename() {
        PsiFile expected = myFixture.addFileToProject("values/trino.yaml", "enabled: true\n");
        assertFile(expected, targets(state("values/trino.yaml"), "trino.yaml"));
    }
    public void testValueSegmentsInsidePathsStillGoToTheValueDeclaration() {
        PsiFile file = state("{{ .Values.envPath }}/values/trino.yaml");
        HelmfileSettings.get(getProject()).select("quality");
        PsiElement[] targets = targets(file, "envPath");
        assertNotNull(targets); assertSize(1, targets);
        assertEquals("envPath", HelmfileSymbols.key(targets[0]).getKeyText());
        assertEquals("k8s/quality", HelmfileSymbols.key(targets[0]).getValueText());
    }
    public void testUnresolvedExpressionsAndMissingFilesDoNotNavigateElsewhere() {
        for (String path : new String[]{"{{ .Values.missing }}/trino.yaml", "{{ exec \"false\" (list) }}/trino.yaml", "missing/trino.yaml"}) {
            PsiElement[] targets = targets(state(path), "trino.yaml");
            assertTrue(path, targets == null || targets.length == 0);
        }
    }
    public void testReusableTemplateValuesFilesResolveRelativeToTheirState() {
        PsiFile expected = myFixture.addFileToProject("config/k8s/quality/trino.yaml", "replicas: 1\n");
        PsiFile file = myFixture.addFileToProject("config/helmfile.yaml.gotmpl", """
                values:
                  - envPath: k8s/quality
                templates:
                  common:
                    values:
                      - '{{ .StateValues.envPath }}/trino.yaml'
                releases: []
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        assertFile(expected, targets(file, "trino.yaml"));
    }
    public void testTemplatedGlobsOfferAllMatchingValuesFiles() {
        PsiFile first = myFixture.addFileToProject("k8s/quality/values/one.yaml", "a: 1\n");
        PsiFile second = myFixture.addFileToProject("k8s/quality/values/two.yaml", "b: 2\n");
        PsiFile file = state("{{ .StateValues.envPath }}/values/*.yaml");
        HelmfileSettings.get(getProject()).select("quality");
        PsiElement[] targets = targets(file, "*.yaml");
        assertNotNull(targets); assertSize(2, targets);
        assertEquals(java.util.Set.of(first.getVirtualFile(), second.getVirtualFile()),
                new java.util.HashSet<>(Arrays.stream(targets).map(target -> target.getContainingFile().getVirtualFile()).toList()));
    }
}
