package dev.helmfile.intellij;

import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.*;
import org.jetbrains.yaml.psi.*;

public class HelmfileRepositoryUsagesTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        HelmfileSettings.get(getProject()).repositoryDownloads(false);
    }
    @Override protected void tearDown() throws Exception {
        try { HelmfileSettings.get(getProject()).loadState(new HelmfileSettings.State()); } finally { super.tearDown(); }
    }
    private YAMLKeyValue declaration(PsiFile file, String name) {
        return PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLKeyValue.class).stream()
                .filter(key -> HelmfileRepositories.isDeclaration(key) && HelmfileSymbols.name(key).equals(name)).findFirst().orElseThrow();
    }
    public void testDeclarationValueFindsChartsTemplatesAndDependenciesOnly() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                repositories:
                  - name: trino
                    url: https://repo.invalid/charts
                templates:
                  common:
                    chart: trino/shared
                releases:
                  - name: trino
                    chart: trino/trino
                    dependencies:
                      - chart: trino/utils
                    values:
                      - chart: trino/not-a-chart-reference
                        text: trino
                """);
        YAMLKeyValue declaration = declaration(file, "trino");
        assertEquals(declaration, HelmfileSymbols.key(declaration.getValue().getFirstChild()));
        assertTrue(new HelmfileFindUsagesHandlerFactory().canFindUsages(declaration.getValue()));
        var refs = HelmfileUsageSearch.find(declaration, GlobalSearchScope.projectScope(getProject()));
        assertSize(3, refs);
        assertTrue(refs.stream().allMatch(ref -> ref.isReferenceTo(declaration) && ref.isReferenceTo(declaration.getValue())));
        assertTrue(refs.stream().allMatch(ref -> ref.getRangeInElement().substring(ref.getElement().getText()).equals("trino")));
        assertEquals("trino", new HelmfileFindUsagesProvider().getDescriptiveName(declaration));
    }
    public void testHyphenatedAliasesRespectDeclarationIdentityAcrossStatesAndBases() {
        PsiFile base = myFixture.addFileToProject("base.yaml", "repositories:\n  - name: charts-core\n    url: https://repo.invalid/charts\n");
        myFixture.addFileToProject("helmfile.yaml.gotmpl", "bases: [base.yaml]\nreleases:\n  - name: one\n    chart: charts-core/api\n");
        myFixture.addFileToProject("child/helmfile.yaml.gotmpl", "bases: [../base.yaml]\nreleases:\n  - name: two\n    chart: 'charts-core/worker'\n");
        PsiFile unrelated = myFixture.addFileToProject("unrelated/helmfile.yaml.gotmpl", "repositories:\n  - name: charts-core\n    url: https://repo.invalid/other\nreleases:\n  - name: other\n    chart: charts-core/api\n");
        assertSize(2, HelmfileUsageSearch.find(declaration(base, "charts-core"), GlobalSearchScope.projectScope(getProject())));
        assertSize(1, HelmfileUsageSearch.find(declaration(unrelated, "charts-core"), GlobalSearchScope.projectScope(getProject())));
    }
    public void testOverriddenRepositoryDoesNotReportUsagesForTheBaseDeclaration() {
        PsiFile base = myFixture.addFileToProject("base.yaml", "repositories:\n  - name: trino\n    url: https://repo.invalid/base\n");
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", "bases: [base.yaml]\nrepositories:\n  - name: trino\n    url: https://repo.invalid/override\nreleases:\n  - name: app\n    chart: trino/trino\n");
        assertEmpty(HelmfileUsageSearch.find(declaration(base, "trino"), GlobalSearchScope.projectScope(getProject())));
        assertSize(1, HelmfileUsageSearch.find(declaration(file, "trino"), GlobalSearchScope.projectScope(getProject())));
    }
    public void testFindUsagesFromTheRepositoryValueUsesTheNativeHandler() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "repositories:\n  - name: 'trino'\n    url: https://repo.invalid\nreleases:\n  - name: app\n    chart: trino/trino\n");
        YAMLKeyValue declaration = declaration(file, "trino");
        var handler = new HelmfileFindUsagesHandlerFactory().createFindUsagesHandler(declaration.getValue(), false);
        assertNotNull(handler);
        List<com.intellij.usageView.UsageInfo> usages = new ArrayList<>();
        assertTrue(handler.processElementUsages(declaration.getValue(), usage -> { usages.add(usage); return true; },
                new com.intellij.find.findUsages.FindUsagesOptions(GlobalSearchScope.projectScope(getProject()))));
        assertSize(1, usages);
        assertSize(1, HelmfileUsageSearch.find(declaration, new LocalSearchScope(file)));
    }
}
