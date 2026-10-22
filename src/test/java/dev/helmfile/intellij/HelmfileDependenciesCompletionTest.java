package dev.helmfile.intellij;

import com.intellij.codeInsight.lookup.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.*;
import org.jetbrains.yaml.psi.YAMLKeyValue;

public class HelmfileDependenciesCompletionTest extends BasePlatformTestCase {
    private static final String RELEASE = """
            environments:
              quality:
                values:
                  - namespace: quality
                  - versions:
                      utils: "1.2.3"
                      api: "2.0.0"
            ---
            templates:
              metoc:
                chart: example/app
            releases:
              - name: track-enrich
                inherit:
                  - template: metoc
                %s
                needs:
                  - {{ .StateValues.namespace }}/rbac
            """;
    @Override protected void tearDown() throws Exception {
        try { HelmfileSettings.get(getProject()).select("default"); } finally { super.tearDown(); }
    }
    private PsiFile configure(String dependency) {
        return myFixture.configureByText("helmfile.yaml.gotmpl", RELEASE.formatted(dependency));
    }
    private List<String> schemaSuggestions() {
        LookupElement[] elements = myFixture.completeBasic();
        if (elements == null) return List.of();
        return Arrays.stream(elements).filter(element -> element.getObject() instanceof HelmfileSchema.Field)
                .map(LookupElement::getLookupString).sorted().toList();
    }
    public void testReleaseOffersDependenciesAlongsideNeeds() {
        configure("<caret>");
        assertContainsElements(schemaSuggestions(), "dependencies", "needs", "chart", "version");
        LookupElement dependency = Arrays.stream(myFixture.getLookupElements()).filter(item -> item.getLookupString().equals("dependencies")).findFirst().orElseThrow();
        myFixture.getLookup().setCurrentItem(dependency);
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        assertTrue(myFixture.getFile().getText().contains("    dependencies: \n"));
    }
    public void testDependencyEntryOffersItsOwnFields() {
        configure("dependencies:\n      - <caret>");
        assertEquals(List.of("alias", "chart", "version"), schemaSuggestions());
    }
    public void testAliasCompletesAndInsertsColonAfterTemplatedVersion() {
        configure("""
                dependencies:
                      - chart: example/utils
                        version: {{ .StateValues.versions.utils }}
                        al<caret>""");
        myFixture.completeBasic();
        if (myFixture.getLookup() != null) {
            LookupElement alias = Arrays.stream(myFixture.getLookupElements()).filter(item -> item.getLookupString().equals("alias")).findFirst().orElseThrow();
            myFixture.getLookup().setCurrentItem(alias);
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        }
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("        alias: \n"));
    }
    public void testStateValuesCompletionWorksInsideDependencyVersion() {
        HelmfileSettings.get(getProject()).select("quality");
        configure("dependencies:\n      - chart: example/utils\n        version: {{ .StateValues.versions.<caret> }}\n        alias: utils");
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "utils", "api");
        assertFalse(myFixture.getLookupElementStrings().contains("chart"));
    }
    public void testTemplateDeclarationsSupportDependenciesAndEntryFields() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "templates:\n  metoc:\n    <caret>\nreleases: []\n");
        assertContainsElements(schemaSuggestions(), "dependencies");
        myFixture.configureByText("helmfile.yaml.gotmpl", "templates:\n  metoc:\n    dependencies:\n      - <caret>\nreleases: []\n");
        assertEquals(List.of("alias", "chart", "version"), schemaSuggestions());
    }
    public void testDependencyFieldsDoNotLeakIntoChartValuesOrDefaults() {
        configure("values:\n      - dependencies:\n          - <caret>");
        assertEmpty(schemaSuggestions());
        myFixture.configureByText("helmfile.yaml.gotmpl", "helmDefaults:\n  <caret>\n");
        assertFalse(schemaSuggestions().contains("dependencies"));
    }
    public void testDependencyDocumentationWorksOnKeysAndLookupItems() {
        PsiFile file = configure("dependencies:\n      - <caret>");
        schemaSuggestions();
        HelmfileDocumentationProvider provider = new HelmfileDocumentationProvider();
        PsiElement context = file.findElementAt(myFixture.getCaretOffset());
        LookupElement alias = Arrays.stream(myFixture.getLookupElements()).filter(item -> item.getLookupString().equals("alias")).findFirst().orElseThrow();
        PsiElement target = provider.getDocumentationElementForLookupItem(file.getManager(), alias.getObject(), context);
        String doc = provider.generateDoc(target, context);
        assertNotNull(doc);
        assertTrue(doc.contains("Alternative name"));
        assertTrue(doc.contains("adding-dependencies-without-forking-the-chart"));
        myFixture.getLookup().hideLookup(true);
        file = configure("dependencies:\n      - chart: example/utils\n        version: {{ .StateValues.versions.utils }}\n        alias: utils");
        YAMLKeyValue key = PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLKeyValue.class).stream()
                .filter(candidate -> candidate.getKeyText().equals("alias")).findFirst().orElseThrow();
        assertTrue(provider.generateDoc(key.getKey(), key.getKey()).contains("Alternative name"));
    }
}
