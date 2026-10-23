package dev.helmfile.intellij;

import com.intellij.codeInsight.lookup.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.*;
import org.jetbrains.yaml.psi.*;

public class HelmfileRepositoryInsightTest extends BasePlatformTestCase {
    static final String INDEX = """
            apiVersion: v1
            entries:
              api:
                - name: api
                  version: 2.0.0
                  description: API service
                - name: api
                  version: 1.0.0
                  description: API service
              worker:
                - name: worker
                  version: 3.0.0
                  deprecated: true
            """;
    private PsiFile configure(String body) {
        return myFixture.configureByText("helmfile.yaml.gotmpl", """
                repositories:
                  - name: example
                    url: https://repo.invalid/%s
                  - name: registry
                    url: registry.invalid/charts
                    oci: true
                ---
                %s
                """.formatted(getTestName(true), body));
    }
    private void cache(PsiFile file) throws Exception {
        var repository = HelmfileRepositories.model(file).named().get("example");
        HelmfileRepositoryService.get(getProject()).remember(new HelmfileRepositoryService.Query(repository.access(), ""), HelmfileChartIndex.parse(INDEX));
    }
    private List<String> suggestions() {
        var elements = myFixture.completeBasic();
        return elements == null ? List.of() : Arrays.stream(elements)
                .filter(element -> element.getObject() instanceof HelmfileRepositoryCompletion.Suggestion).map(LookupElement::getLookupString).toList();
    }
    private List<String> problems() {
        myFixture.enableInspections(new HelmfileRepositoryInspection());
        return myFixture.doHighlighting().stream().map(info -> info.getDescription()).filter(Objects::nonNull)
                .filter(description -> description.contains("repository") || description.contains("Repository") || description.contains("was not found"))
                .toList();
    }
    public void testAliasesCompleteAfterDocumentSeparator() {
        configure("releases:\n  - name: app\n    chart: <caret>\n");
        assertContainsElements(suggestions(), "example/", "registry/");
        LookupElement alias = Arrays.stream(myFixture.getLookupElements()).filter(item -> item.getLookupString().equals("example/")).findFirst().orElseThrow();
        myFixture.getLookup().setCurrentItem(alias);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        assertTrue(myFixture.getFile().getText().contains("chart: example/\n"));
    }
    public void testChartCompletionReplacesFullQuotedPrefix() throws Exception {
        PsiFile file = configure("releases:\n  - name: app\n    chart: 'example/<caret>'\n");
        cache(file);
        assertContainsElements(suggestions(), "example/api", "example/worker");
        LookupElement chart = Arrays.stream(myFixture.getLookupElements()).filter(item -> item.getLookupString().equals("example/api")).findFirst().orElseThrow();
        myFixture.getLookup().setCurrentItem(chart);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        assertTrue(file.getText(), file.getText().contains("chart: 'example/api'\n"));
    }
    public void testPinnedVersionsCompleteNewestFirst() throws Exception {
        PsiFile file = configure("releases:\n  - name: app\n    chart: example/api\n    version: <caret>\n");
        cache(file);
        assertEquals(List.of("2.0.0", "1.0.0"), suggestions());
    }
    public void testChartAndVersionCompletionInDependenciesAndTemplates() throws Exception {
        PsiFile file = configure("templates:\n  common:\n    dependencies:\n      - chart: example/<caret>\n");
        cache(file);
        assertContainsElements(suggestions(), "example/api", "example/worker");
        myFixture.getLookup().hideLookup(true);
        file = configure("releases:\n  - name: app\n    dependencies:\n      - chart: example/api\n        version: <caret>\n");
        cache(file);
        assertEquals(List.of("2.0.0", "1.0.0"), suggestions());
    }
    public void testVersionCompletionFollowsInheritedChart() throws Exception {
        PsiFile file = configure("""
                templates:
                  common:
                    chart: example/api
                releases:
                  - name: app
                    inherit:
                      - template: common
                    version: <caret>
                """);
        cache(file);
        assertEquals(List.of("2.0.0", "1.0.0"), suggestions());
    }
    public void testRepositoriesFromPlainYamlBaseCompleteAndNavigate() {
        myFixture.addFileToProject("base.yaml", "repositories:\n  - name: inherited\n    url: https://repo.invalid/base\n");
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "bases: [base.yaml]\nreleases:\n  - name: app\n    chart: <caret>\n");
        assertContainsElements(suggestions(), "inherited/");
        if (myFixture.getLookup() != null) myFixture.getLookup().hideLookup(true);
        file = myFixture.configureByText("helmfile.yaml.gotmpl", "bases: [base.yaml]\nreleases:\n  - name: app\n    chart: 'inherited/api'\n");
        YAMLScalar scalar = PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLScalar.class).stream()
                .filter(value -> value.getTextValue().equals("inherited/api")).findFirst().orElseThrow();
        var reference = HelmfileRepositories.reference(scalar);
        assertNotNull(reference.resolve());
        assertEquals("base.yaml", reference.resolve().getContainingFile().getName());
        assertEquals("inherited", reference.getRangeInElement().substring(scalar.getText()));
        var targets = new HelmfileGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(scalar.getTextOffset() + 2), scalar.getTextOffset() + 2, myFixture.getEditor());
        assertEquals("base.yaml", targets[0].getContainingFile().getName());
    }
    public void testUnknownAliasesChartsAndVersionsAreInspected() throws Exception {
        PsiFile file = configure("""
                releases:
                  - name: missing-repository
                    chart: typo/api
                  - name: missing-chart
                    chart: example/missing
                  - name: missing-version
                    chart: example/api
                    version: 9.0.0
                """);
        cache(file);
        assertContainsElements(problems(), "Repository 'typo' is not declared in this Helmfile or its bases",
                "Chart 'missing' was not found in repository 'example'", "Version '9.0.0' was not found for chart 'example/api'");
    }
    public void testLocalUrlsTemplatesAndVersionConstraintsHaveNoFalseWarnings() throws Exception {
        myFixture.addFileToProject("charts/local/Chart.yaml", "apiVersion: v2\nname: local\nversion: 1.0.0\n");
        PsiFile file = configure("""
                releases:
                  - name: local
                    chart: charts/local
                  - name: explicit
                    chart: ./missing-local/chart
                  - name: url
                    chart: https://repo.invalid/chart.tgz
                  - name: plugin
                    chart: git::https://repo.invalid/charts
                  - name: templated
                    chart: '{{ .Values.chart }}'
                  - name: valid
                    chart: example/api
                    version: ^2.0.0
                  - name: partial
                    chart: example/api
                    version: "2"
                  - name: unknown-index
                    chart: registry/api
                    version: 1.0.0
                """);
        cache(file);
        assertEmpty(problems());
    }
    public void testOfflineIndexDoesNotInventMissingChartsOrVersions() {
        configure("releases:\n  - name: app\n    chart: example/api\n    version: 99.0.0\n");
        assertEmpty(problems());
    }
    public void testMissingBaseDoesNotMisdiagnoseItsRepositoryAliases() {
        configure("bases: [missing.yaml]\nreleases:\n  - name: app\n    chart: from-base/api\n");
        assertEmpty(problems());
    }
    public void testDuplicateAndIncompleteRepositoryDeclarationsAreInspected() {
        myFixture.configureByText("helmfile.yaml.gotmpl", """
                repositories:
                  - name: duplicate
                    url: https://repo.invalid/one
                  - name: duplicate
                    url: https://repo.invalid/two
                  - name: no-url
                  - url: https://repo.invalid/no-name
                releases: []
                """);
        assertContainsElements(problems(), "Duplicate Helm repository name 'duplicate'", "Helm repository requires a URL", "Helm repository requires a name");
    }
    public void testRepositoryMetadataDoesNotLeakIntoInlineChartValues() throws Exception {
        PsiFile file = configure("releases:\n  - name: app\n    values:\n      - chart: <caret>\n");
        cache(file);
        assertEmpty(suggestions());
        if (myFixture.getLookup() != null) myFixture.getLookup().hideLookup(true);
        configure("releases:\n  - name: app\n    values:\n      - chart: typo/api\n        version: 9.0.0\n");
        assertEmpty(problems());
    }
    public void testGoCompletionInsideChartStillOffersStateValues() {
        configure("values:\n  - chartName: api\n    chartVersion: 1.0.0\nreleases:\n  - name: app\n    chart: example/{{ .Values.<caret> }}\n");
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "chartName", "chartVersion");
    }
    public void testOciVersionsUseRegistryTagsWhenAvailable() {
        PsiFile file = configure("releases:\n  - name: app\n    chart: registry/api\n    version: <caret>\n");
        var query = HelmfileRepositories.query(file, "registry/api");
        HelmfileRepositoryService.get(getProject()).remember(query, new HelmfileChartIndex(Map.of("api", List.of(
                new HelmfileChartIndex.Chart("api", "1.2.0", "OCI chart", false), new HelmfileChartIndex.Chart("api", "1.1.0", "OCI chart", false)))));
        assertEquals(List.of("1.2.0", "1.1.0"), suggestions());
    }
    public void testStateValueRepositoryUrlResolvesWithoutRunningFunctions() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                values:
                  - repoUrl: https://repo.invalid/charts
                repositories:
                  - name: example
                    url: '{{ .StateValues.repoUrl }}'
                releases: []
                """);
        assertEquals("https://repo.invalid/charts", HelmfileRepositories.model(file).named().get("example").access().url());
    }
    public void testUnquotedStateValueRepositoryUrlResolvesWithoutRunningFunctions() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                values:
                  - repoUrl: https://repo.invalid/charts
                repositories:
                  - name: example
                    url: {{ .StateValues.repoUrl }}
                releases: []
                """);
        assertEquals("https://repo.invalid/charts", HelmfileRepositories.model(file).named().get("example").access().url());
    }
    public void testYamlAndTemplateViewsShareCachesAndInvalidateAfterAnEdit() {
        PsiFile file = configure("values:\n  - namespace: quality\ntemplates:\n  common:\n    chart: example/api\nreleases: []\n");
        PsiFile yaml = HelmfileValues.yaml(file);
        var repositories = HelmfileRepositories.model(file);
        assertSame(repositories, HelmfileRepositories.model(yaml));
        assertSame(HelmfileValues.model(file), HelmfileValues.model(yaml));
        assertSame(HelmfileTemplates.declarations(file), HelmfileTemplates.declarations(yaml));
        var document = myFixture.getEditor().getDocument();
        int offset = document.getText().indexOf("https://repo.invalid/");
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), () ->
                document.replaceString(offset, offset + "https://repo.invalid/".length(), "https://updated.invalid/"));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertNotSame(repositories, HelmfileRepositories.model(file));
        assertTrue(HelmfileRepositories.model(yaml).named().get("example").access().url().startsWith("https://updated.invalid/"));
    }
    public void testStateCompletionAndForwardNavigationWorkDuringIndexing() throws Throwable {
        PsiFile file = configure("""
                values:
                  - namespace: quality
                    name: app
                releases:
                  - name: app
                    namespace: {{ .Values.namespace }}
                    chart: example/{{ .StateValues.<caret> }}
                """);
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertTrue(com.intellij.openapi.project.DumbService.isDumb(getProject()));
            myFixture.completeBasic();
            assertContainsElements(myFixture.getLookupElementStrings(), "namespace", "name");
            if (myFixture.getLookup() != null) myFixture.getLookup().hideLookup(true);
            int offset = file.getText().indexOf(".Values.namespace") + ".Values.".length() + 2;
            var targets = new HelmfileGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.getEditor());
            assertNotNull(targets);
            assertEquals("namespace", targets[0].getText());
        });
    }
}
