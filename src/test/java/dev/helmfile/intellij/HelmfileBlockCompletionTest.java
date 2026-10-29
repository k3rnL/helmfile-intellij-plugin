package dev.helmfile.intellij;

import com.intellij.codeInsight.lookup.*;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.*;

public class HelmfileBlockCompletionTest extends BasePlatformTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
    }
    @Override protected void tearDown() throws Exception {
        try {
            if (myFixture.getEditor() != null) TemplateManager.getInstance(getProject()).finishTemplate(myFixture.getEditor());
            HelmfileSettings.get(getProject()).repositoryDownloads(true);
        } finally { super.tearDown(); }
    }
    private void choose(String name) {
        myFixture.completeBasic();
        if (myFixture.getLookup() != null) {
            LookupElement item = Arrays.stream(myFixture.getLookupElements()).filter(value -> value.getLookupString().equals(name)).findFirst().orElseThrow();
            myFixture.getLookup().setCurrentItem(item);
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        }
        assertNotNull(myFixture.getEditor().getDocument().getText(), TemplateManager.getInstance(getProject()).getActiveTemplate(myFixture.getEditor()));
    }
    private void next() {
        if (myFixture.getLookup() != null) myFixture.getLookup().hideLookup(true);
        myFixture.performEditorAction("NextTemplateVariable");
    }
    public void testReleaseSectionExpandsAndTabMovesThroughRequiredFields() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "rel<caret>\n");
        choose("releases");
        myFixture.type("api"); next();
        myFixture.type("example/api"); next();
        myFixture.type("1.2.3"); next();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("releases:\n  - name: api\n    chart: example/api\n    version: 1.2.3"));
        assertNull(TemplateManager.getInstance(getProject()).getActiveTemplate(myFixture.getEditor()));
    }
    public void testDependencyEntryAndSectionUseTheReleaseIndentation() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "releases:\n  - name: api\n    dep<caret>\n");
        choose("dependencies");
        myFixture.type("example/utils"); next(); myFixture.type("2.0.0"); next();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("    dependencies:\n      - chart: example/utils\n        version: 2.0.0"));
        myFixture.configureByText("helmfile.yaml.gotmpl", "releases:\n  - name: api\n    dependencies:\n      - dep<caret>\n");
        choose("dependency");
        myFixture.type("example/utils"); next(); myFixture.type("2.0.0"); next();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("      - chart: example/utils\n        version: 2.0.0"));
    }
    public void testRepositoryBlockHasNameAndUrl() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "repo<caret>\n");
        choose("repositories");
        myFixture.type("example"); next(); myFixture.type("https://repo.invalid/charts"); next();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("repositories:\n  - name: example\n    url: https://repo.invalid/charts"));
    }
    public void testChosenChartProvidesMatchingVersionSuggestionsAndLatestDefault() {
        HelmfileSettings.get(getProject()).repositoryDownloads(false);
        var file = myFixture.configureByText("helmfile.yaml.gotmpl", "repositories:\n  - name: example\n    url: https://repo.invalid/charts\nreleases:\n  - rel<caret>\n");
        var query = HelmfileRepositories.query(file, "example/api");
        HelmfileRepositoryService.get(getProject()).remember(query, new HelmfileChartIndex(Map.of("api", List.of(
                new HelmfileChartIndex.Chart("api", "2.0.0", "api", false), new HelmfileChartIndex.Chart("api", "1.0.0", "api", false)))));
        choose("release"); myFixture.type("api"); next(); myFixture.type("example/api"); next();
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("version: 2.0.0"));
        if (myFixture.getLookup() != null) assertContainsElements(myFixture.getLookupElementStrings(), "2.0.0", "1.0.0");
        next();
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEmpty(com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), com.intellij.psi.PsiErrorElement.class));
    }
    public void testBlocksAreNotOfferedInsideInlineChartValues() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "releases:\n  - name: api\n    values:\n      - dep<caret>\n");
        var items = myFixture.completeBasic();
        assertTrue(items == null || Arrays.stream(items).noneMatch(item -> item.getLookupString().equals("dependency")));
    }
}
