package dev.helmfile.intellij;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.*;
import org.jetbrains.yaml.YAMLFileType;
import org.jetbrains.yaml.psi.*;

public class HelmfileInsightTest extends BasePlatformTestCase {
    private static final String MULTI_ENVIRONMENT_STATE = """
            bases:
              - base.yaml
            environments:
              awsqt:
                values:
                  - namespace: quality
                  - createNamespace: true
                  - versions:
                      default: "1.0"
                      storage: "1.1"
                  - envPath: k8s/awsqt
              awsqo:
                values:
                  - namespace: staging
                  - versions:
                      default: "2.0"
                  - envPath: k8s/awsqo
              prod:
                values:
                  - namespace: production
                  - versions:
                      default: "3.0"
                      cli: "1.2"
                  - envPath: k8s/prod
            ---
            templates:
              app:
                namespace: {{ .StateValues.namespace }}
                version: '{{`{{ index .StateValues.versions "api" }}`}}'
            releases:
              - name: extraction-service
                inherit:
                  - template: app
                values:
                  - chartOnly: true
                needs:
                  - {{ COMPLETION }}/spark-jars
            """;
    @Override protected void tearDown() throws Exception {
        try { HelmfileSettings.get(getProject()).select("default"); } finally { super.tearDown(); }
    }
    public void testRecognizesTemplatesAndKeepsPlainYaml() {
        assertSame(HelmfileFileType.INSTANCE, myFixture.configureByText("helmfile.yaml", "releases: []").getFileType());
        assertSame(HelmfileFileType.INSTANCE, myFixture.configureByText("values.yaml.gotmpl", "x: {{ .Values.x }}").getFileType());
        assertSame(YAMLFileType.YML, myFixture.configureByText("values.yaml", "x: 1").getFileType());
    }
    public void testYamlTreeSurvivesInlineTemplatesAndControlBlocks() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                values:
                  - app: api
                {{ if .Values.enabled }}
                releases:
                  - name: {{ .Values.app }}
                    chart: stable/api
                    namespace: "{{ .Environment.Name }}"
                {{ end }}
                """);
        YAMLFile yaml = HelmfileValues.yaml(file);
        assertNotNull(yaml);
        YAMLMapping root = HelmfileValues.roots(yaml).getFirst();
        assertNotNull(root.getKeyValueByKey("releases"));
        assertTrue(PsiTreeUtil.findChildrenOfType(yaml, YAMLKeyValue.class).stream().anyMatch(k -> k.getKeyText().equals("chart") && k.getValueText().equals("stable/api")));
        assertEquals(file.getText(), yaml.getText());
    }
    public void testContextualYamlCompletion() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "releases:\n  - name: api\n    na<caret>\n");
        myFixture.completeBasic();
        assertTrue(myFixture.getLookupElementStrings().contains("namespace"));
        assertFalse(myFixture.getLookupElementStrings().contains("repositories"));
    }
    public void testNoSchemaCompletionInsideReleaseValues() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "releases:\n  - name: api\n    values:\n      - custom:\n          na<caret>\n");
        List<String> path = HelmfileSchema.completionPath(file, myFixture.getCaretOffset());
        assertEquals(List.of("releases", "values", "custom"), path);
        assertTrue(HelmfileSchema.fields(path).isEmpty());
    }
    public void testFunctionsAndRootContextComplete() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "x: {{ requ<caret> }}");
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "requiredEnv", "required");
        myFixture.configureByText("helmfile.yaml.gotmpl", "x: {{ .Env<caret> }}");
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), ".Environment.Name", ".Environment.Values");
    }
    public void testNestedValueCompletion() {
        myFixture.addFileToProject("state.yaml", "image:\n  tag: v1\n  repository: example/api\n");
        myFixture.configureByText("helmfile.yaml.gotmpl", "values:\n  - state.yaml\nreleases:\n  - name: api\n    chart: {{ .Values.image.<caret> }}\n");
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "tag", "repository");
        assertFalse(myFixture.getLookupElementStrings().contains("requiredEnv"));
    }
    public void testStateValuesCompletionDiscoversDeclaredEnvironmentsAndShowsTheirNames() {
        myFixture.addFileToProject("base.yaml", "values:\n  - jsonPatches: {}\n");
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl",
                MULTI_ENVIRONMENT_STATE.replace("COMPLETION", ".StateValues.<caret>namespace"));
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "namespace", "createNamespace", "versions", "envPath", "jsonPatches");
        assertFalse(myFixture.getLookupElementStrings().contains("chartOnly"));
        List<String> labels = completionSourceLabels("namespace");
        assertSize(3, labels);
        assertTrue(labels.stream().allMatch(label -> label.contains("helmfile.yaml.gotmpl")));
        for (String environment : List.of("awsqt", "awsqo", "prod")) assertTrue(labels.toString(), labels.stream().anyMatch(label -> label.contains(environment)));
        // Suggestions from other environments must not change the runtime resolution context.
        assertFalse(HelmfileValues.model(file).definitions().containsKey("namespace"));
        assertEquals("default", HelmfileSettings.get(getProject()).environment());
        HelmfileSettings.get(getProject()).select("prod");
        List<HelmfileValues.Definition> selected = HelmfileValues.model(file).completionDefinitions().stream()
                .filter(definition -> definition.path().equals("namespace")).toList();
        assertSize(1, selected);
        assertEquals("prod", selected.getFirst().environment());
        assertEquals("production", selected.getFirst().preview());
    }
    public void testStateValuesNestedCompletionAcrossEnvironments() {
        myFixture.configureByText("helmfile.yaml.gotmpl", MULTI_ENVIRONMENT_STATE.replace("COMPLETION", ".StateValues.versions.<caret>"));
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "default", "storage", "cli");
        assertFalse(myFixture.getLookupElementStrings().contains("namespace"));
        assertEquals(List.of("  helmfile.yaml.gotmpl · prod"), completionSourceLabels("cli"));
    }
    public void testSelectedEnvironmentNarrowsStateValuesSuggestions() {
        HelmfileSettings.get(getProject()).select("awsqt");
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", MULTI_ENVIRONMENT_STATE.replace("COMPLETION", ".StateValues.<caret>"));
        myFixture.completeBasic();
        assertEquals(List.of("  helmfile.yaml.gotmpl · awsqt"), completionSourceLabels("namespace"));
        assertEquals("quality", HelmfileValues.model(file).definitions().get("namespace").preview());
        myFixture.configureByText("helmfile.yaml.gotmpl", MULTI_ENVIRONMENT_STATE.replace("COMPLETION", ".StateValues.versions.<caret>"));
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "default", "storage");
        assertFalse(myFixture.getLookupElementStrings().contains("cli"));
    }
    public void testInheritedEnvironmentFilesUseTheirOwnEnvironmentForCompletion() {
        myFixture.addFileToProject("base.yaml", "environments:\n  awsqt:\n    values:\n      - config/{{ .Environment.Name }}.yaml\n");
        myFixture.addFileToProject("config/awsqt.yaml", "namespace: quality\nversions:\n  api: \"1.0\"\n");
        myFixture.configureByText("helmfile.yaml.gotmpl", "bases:\n  - base.yaml\n---\nreleases:\n  - name: api\n    namespace: {{ .StateValues.<caret> }}\n");
        myFixture.completeBasic();
        assertContainsElements(myFixture.getLookupElementStrings(), "namespace", "versions");
        assertEquals(List.of("  awsqt.yaml · awsqt"), completionSourceLabels("namespace"));
    }
    public void testEnvironmentValueCompletionDocumentationUsesTheSuggestedEnvironment() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", MULTI_ENVIRONMENT_STATE.replace("COMPLETION", ".StateValues.<caret>"));
        myFixture.completeBasic();
        LookupElement production = Arrays.stream(myFixture.getLookupElements())
                .filter(element -> element.getLookupString().equals("namespace"))
                .filter(element -> element.getObject() instanceof HelmfileValues.Definition definition && "prod".equals(definition.environment()))
                .findFirst().orElseThrow();
        HelmfileDocumentationProvider provider = new HelmfileDocumentationProvider();
        PsiElement context = file.findElementAt(myFixture.getCaretOffset());
        PsiElement target = provider.getDocumentationElementForLookupItem(file.getManager(), production.getObject(), context);
        String documentation = provider.generateDoc(target, context);
        assertNotNull(documentation);
        assertTrue(documentation.contains("production"));
        assertTrue(documentation.contains("environment prod"));
        assertFalse(documentation.contains("environment default"));
    }
    private List<String> completionSourceLabels(String name) {
        LookupElement[] elements = myFixture.getLookupElements();
        assertNotNull(elements);
        return Arrays.stream(elements).filter(element -> element.getLookupString().equals(name)).map(element -> {
            LookupElementPresentation presentation = new LookupElementPresentation();
            element.renderElement(presentation);
            return presentation.getTailText();
        }).toList();
    }
    public void testValuesNavigationAndAliases() {
        myFixture.addFileToProject("state.yaml", "image:\n  tag: v1\n");
        for (String root : List.of(".Values", ".StateValues", ".Environment.Values", "$.Values")) {
            PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "values:\n  - state.yaml\nx: {{ " + root + ".image.ta<caret>g }}\n");
            PsiReference reference = file.findReferenceAt(myFixture.getCaretOffset());
            assertNotNull(root, reference);
            PsiElement target = reference.resolve();
            assertNotNull(root, target);
            assertEquals("tag", target.getText());
            assertEquals("state.yaml", target.getContainingFile().getName());
        }
    }
    public void testIndexSupportsKeysContainingDotsAndDashes() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "values:\n  - image.tag: dotted\n    image-tag: dashed\nx: {{ index .Values \"image.<caret>tag\" }}\n");
        PsiReference ref = file.findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(ref); assertNotNull(ref.resolve());
        assertEquals("image.tag", ref.resolve().getText());
    }
    public void testDefaultEnvironmentAndReleaseValuesAreIsolated() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                values:
                  - name: shared
                environments:
                  default:
                    values:
                      - defaultOnly: true
                  production:
                    values:
                      - productionOnly: true
                releases:
                  - name: api
                    values:
                      - chartOnly: true
                """);
        Map<String, HelmfileValues.Definition> values = HelmfileValues.model(file).definitions();
        assertTrue(values.containsKey("defaultOnly"));
        assertFalse(values.containsKey("productionOnly")); assertFalse(values.containsKey("chartOnly"));
        assertFalse(HelmfileValues.model(file).completionDefinitions().stream().anyMatch(definition -> definition.path().equals("productionOnly")));
        HelmfileSettings.get(getProject()).select("production");
        values = HelmfileValues.model(file).definitions();
        assertTrue(values.containsKey("productionOnly")); assertFalse(values.containsKey("defaultOnly"));
        assertFalse(HelmfileValues.model(file).completionDefinitions().stream().anyMatch(definition -> definition.path().equals("defaultOnly")));
    }
    public void testBasesAndOverridesUseSourceOrderAndReplaceMapsWithScalars() {
        myFixture.addFileToProject("base.yaml", "values:\n  - image:\n      tag: base\n      repository: example/api\nenvironments:\n  production:\n    values:\n      - image:\n          tag: env-base\n");
        myFixture.addFileToProject("prod.yaml", "image:\n  tag: production\n");
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "bases:\n  - base.yaml\nvalues:\n  - image:\n      tag: root\nenvironments:\n  production:\n    values:\n      - prod.yaml\n");
        HelmfileSettings.get(getProject()).select("production");
        var values = HelmfileValues.model(file).definitions();
        assertEquals("production", values.get(HelmfileValues.key(List.of("image", "tag"))).preview());
        assertEquals("example/api", values.get(HelmfileValues.key(List.of("image", "repository"))).preview());
        PsiFile replaced = myFixture.configureByText("helmfile.yaml.gotmpl", "bases:\n  - base.yaml\nvalues:\n  - image: disabled\n");
        HelmfileSettings.get(getProject()).select("default");
        assertFalse(HelmfileValues.model(replaced).definitions().containsKey(HelmfileValues.key(List.of("image", "tag"))));
    }
    public void testRelativePathsGlobsAndTemplatedEnvironmentPaths() {
        myFixture.addFileToProject("config/default/a.yaml", "first: one\n");
        myFixture.addFileToProject("config/default/b.yaml", "second: two\n");
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "values:\n  - config/{{ .Environment.Name }}/*.yaml\n");
        var values = HelmfileValues.model(file).definitions();
        assertTrue(values.containsKey("first")); assertTrue(values.containsKey("second"));
    }
    public void testCycleInBasesTerminates() {
        myFixture.addFileToProject("base.yaml", "bases:\n  - helmfile.yaml.gotmpl\nvalues:\n  - fromBase: true\n");
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "bases:\n  - base.yaml\nvalues:\n  - fromRoot: true\n");
        assertTrue(HelmfileValues.model(file).definitions().containsKey("fromRoot"));
    }
    public void testReferencedTemplateUsesNearbyState() {
        myFixture.addFileToProject("helmfile.yaml", "values:\n  - domain: example.test\nreleases: []\n");
        PsiFile file = myFixture.configureByText("config.yaml.gotmpl", "domain: {{ .Values.domain }}\n");
        assertEquals("example.test", HelmfileValues.model(file).definitions().get("domain").preview());
    }
    public void testDocumentationAndIntentionAreAvailable() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "x: {{ requ<caret>iredEnv \"TAG\" }}");
        PsiElement element = file.findElementAt(myFixture.getCaretOffset());
        String doc = new HelmfileDocumentationProvider().generateDoc(element, element);
        assertNotNull(doc); assertTrue(doc.contains("requiredEnv")); assertTrue(doc.contains("Reference"));
        assertTrue(new FunctionDocumentationIntention().isAvailable(getProject(), myFixture.getEditor(), element));
        assertNotNull(myFixture.findSingleIntention("Show Go template function documentation"));
    }
    public void testFunctionNamesInsideStringsDoNotGetFunctionDocumentation() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "x: {{ printf \"requ<caret>iredEnv\" }}");
        assertNull(HelmfileDocumentationProvider.functionAt(file, myFixture.getCaretOffset()));
    }
    public void testDocumentationDuringCompletionAndOnValueSegment() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "values:\n  - image:\n      tag: v1\nx: {{ .Values.image.ta<caret>g }}\n");
        HelmfileDocumentationProvider provider = new HelmfileDocumentationProvider();
        PsiElement element = file.findElementAt(myFixture.getCaretOffset());
        PsiElement target = provider.getCustomDocumentationElement(myFixture.getEditor(), file, element, myFixture.getCaretOffset());
        assertNotNull(target);
        String doc = provider.generateDoc(target, element);
        assertTrue(doc.contains("v1")); assertTrue(doc.contains("default"));
        PsiElement lookup = provider.getDocumentationElementForLookupItem(file.getManager(), FunctionCatalog.find("requiredEnv"), element);
        assertTrue(provider.generateDoc(lookup, element).contains("requiredEnv name"));
    }
    public void testFileReferencesNavigateFromYamlAndGoStrings() {
        myFixture.addFileToProject("state.yaml", "name: api\n");
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "values:\n  - state.yaml\nx: {{ readFile \"sta<caret>te.yaml\" }}\n");
        PsiReference template = file.findReferenceAt(myFixture.getCaretOffset());
        assertNotNull(template); assertEquals("state.yaml", template.resolve().getContainingFile().getName());
        YAMLFile yaml = HelmfileValues.yaml(file);
        YAMLScalar scalar = PsiTreeUtil.findChildrenOfType(yaml, YAMLScalar.class).stream().filter(s -> s.getText().equals("state.yaml")).findFirst().orElseThrow();
        PsiReference[] refs = scalar.getReferences();
        assertTrue(Arrays.stream(refs).anyMatch(r -> r.resolve() instanceof PsiFile target && target.getName().equals("state.yaml")));
    }
    public void testTemplateDiagnostics() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "x: <error descr=\"Unclosed Go template action; expected }}\">{{ .Values.name</error>");
        myFixture.checkHighlighting();
    }
}
