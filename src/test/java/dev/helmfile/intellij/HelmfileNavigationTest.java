package dev.helmfile.intellij;

import com.intellij.codeInsight.daemon.*;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.*;
import org.jetbrains.yaml.psi.*;

public class HelmfileNavigationTest extends BasePlatformTestCase {
    @Override protected void tearDown() throws Exception {
        try { HelmfileSettings.get(getProject()).select("default"); } finally { super.tearDown(); }
    }
    private YAMLKeyValue key(PsiFile file, String name) {
        return PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLKeyValue.class).stream()
                .filter(key -> key.getKeyText().equals(name)).findFirst().orElseThrow();
    }
    public void testCtrlClickOffersEnvironmentDeclarationsAndNarrowsWhenSelected() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                environments:
                  quality:
                    values:
                      - namespace: quality
                  production:
                    values:
                      - namespace: production
                ---
                releases:
                  - name: api
                    namespace: {{ .StateValues.name<caret>space }}
                """);
        PsiElement source = file.findElementAt(myFixture.getCaretOffset());
        HelmfileGotoDeclarationHandler handler = new HelmfileGotoDeclarationHandler();
        PsiElement[] targets = handler.getGotoDeclarationTargets(source, myFixture.getCaretOffset(), myFixture.getEditor());
        assertNotNull(targets); assertSize(1, targets);
        assertInstanceOf(targets[0], HelmfileNavigationRequest.class);
        assertTrue(((HelmfileNavigationRequest) targets[0]).popup.isDeclarationMode());
        assertSize(2, ((HelmfileNavigationRequest) targets[0]).popup.results());
        HelmfileSettings.get(getProject()).select("production");
        targets = handler.getGotoDeclarationTargets(source, myFixture.getCaretOffset(), myFixture.getEditor());
        assertSize(1, targets);
        assertEquals("production", HelmfileSymbols.key(targets[0]).getValueText());
    }
    public void testFindUsagesAndCtrlClickDeclarationReachTemplateUsagesAcrossFiles() {
        PsiFile values = myFixture.addFileToProject("state.yaml", "image:\n  tag: v1\n");
        PsiFile state = myFixture.addFileToProject("helmfile.yaml.gotmpl", "values:\n  - state.yaml\nreleases:\n  - name: api\n    version: {{ .Values.image.tag }}\n    values:\n      - app.yaml.gotmpl\n");
        myFixture.addFileToProject("app.yaml.gotmpl", "tag: {{ .StateValues.image.tag }}\n");
        YAMLKeyValue tag = key(values, "tag");
        var refs = HelmfileUsageSearch.find(tag, GlobalSearchScope.projectScope(getProject()));
        assertSize(2, refs);
        assertEquals(Set.of("helmfile.yaml.gotmpl", "app.yaml.gotmpl"), new HashSet<>(refs.stream().map(r -> r.getElement().getContainingFile().getName()).toList()));
        myFixture.configureFromExistingVirtualFile(values.getVirtualFile());
        int offset = tag.getKey().getTextOffset();
        PsiElement[] usages = new HelmfileGotoDeclarationHandler().getGotoDeclarationTargets(tag.getKey(), offset, myFixture.getEditor());
        assertNotNull(usages); assertSize(1, usages);
        assertInstanceOf(usages[0], HelmfileNavigationRequest.class);
        assertSize(2, ((HelmfileNavigationRequest) usages[0]).popup.results());
        assertTrue(new HelmfileFindUsagesHandlerFactory().canFindUsages(tag));
        assertNotNull(new HelmfileFindUsagesHandlerFactory().createFindUsagesHandler(tag, false));
    }
    public void testInheritanceReferencesAndReverseUsagesFollowBases() {
        PsiFile base = myFixture.addFileToProject("base.yaml", "templates:\n  shared:\n    chart: example/api\n  child:\n    inherit:\n      - template: shared\n");
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", """
                bases:
                  - base.yaml
                ---
                releases:
                  - name: api
                    inherit:
                      - template: shared
                  - name: worker
                    inherit:
                      - template: child
                """);
        YAMLKeyValue shared = key(base, "shared");
        var refs = HelmfileUsageSearch.find(shared, GlobalSearchScope.projectScope(getProject()));
        assertSize(2, refs);
        assertEquals(Set.of("child", "api"), new HashSet<>(refs.stream().map(reference -> HelmfileTemplates.owner((YAMLScalar) reference.getElement()))
                .map(owner -> HelmfileTemplates.isDeclaration(owner) ? owner.getKeyText() : owner.getValueText()).toList()));
        YAMLScalar usage = PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLScalar.class).stream()
                .filter(scalar -> scalar.getTextValue().equals("shared") && HelmfileTemplates.isInheritance(scalar)).findFirst().orElseThrow();
        assertTrue(Arrays.stream(usage.getReferences()).anyMatch(reference -> reference.isReferenceTo(shared)));
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        PsiElement[] targets = new HelmfileGotoDeclarationHandler().getGotoDeclarationTargets(usage.getFirstChild(), usage.getTextOffset(), myFixture.getEditor());
        assertNotNull(targets); assertSize(1, targets); assertEquals(shared, targets[0]);
    }
    public void testGutterArrowsPointToParentAndInheritingReleases() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", "templates:\n  shared:\n    chart: example/api\nreleases:\n  - name: api\n    inherit:\n      - template: shared\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        HelmfileInheritanceLineMarkerProvider provider = new HelmfileInheritanceLineMarkerProvider();
        List<RelatedItemLineMarkerInfo<?>> markers = new ArrayList<>();
        PsiTreeUtil.processElements(HelmfileValues.yaml(file), element -> { provider.collectNavigationMarkers(element, markers); return true; });
        assertSize(2, markers);
        assertTrue(markers.stream().anyMatch(marker -> marker.getLineMarkerTooltip().contains("parent Helmfile")));
        assertTrue(markers.stream().anyMatch(marker -> marker.getLineMarkerTooltip().contains("inheriting Helmfile")));
        assertTrue(markers.stream().allMatch(marker -> marker.getNavigationHandler() != null && marker.getElement().getFirstChild() == null));
    }
    public void testLocalTemplateOverridesBaseAndUnrelatedNamesAreExcluded() {
        myFixture.addFileToProject("base.yaml", "templates:\n  shared:\n    chart: example/base\n");
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", "bases:\n  - base.yaml\ntemplates:\n  shared:\n    chart: example/local\nreleases:\n  - name: api\n    inherit:\n      - template: shared\n");
        PsiFile unrelated = myFixture.addFileToProject("unrelated/helmfile.yaml.gotmpl", "templates:\n  shared:\n    chart: example/other\nreleases:\n  - name: other\n    inherit:\n      - template: shared\n");
        YAMLKeyValue shared = key(file, "shared");
        assertSize(1, HelmfileUsageSearch.find(shared, GlobalSearchScope.projectScope(getProject())));
        assertSize(1, HelmfileUsageSearch.find(key(unrelated, "shared"), GlobalSearchScope.projectScope(getProject())));
    }
    public void testEscapedTemplateValueReferencesNavigateAndAppearInFindUsages() {
        PsiFile state = myFixture.addFileToProject("helmfile.yaml.gotmpl", "values:\n  - versions:\n      api: v1\nreleases:\n  - name: api\n    version: '{{`{{ .StateValues.versions.api | quote }}`}}'\n");
        YAMLKeyValue declaration = key(state, "api");
        int offset = state.getText().indexOf("versions.api") + "versions.".length();
        PsiReference reference = state.findReferenceAt(offset);
        assertNotNull(reference); assertNotNull(reference.resolve());
        assertTrue(reference.isReferenceTo(declaration));
        assertSize(1, HelmfileUsageSearch.find(declaration, GlobalSearchScope.projectScope(getProject())));
    }
    public void testEnvironmentDeclarationsFindOnlyUsagesWhichCanResolveToThem() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", "environments:\n  quality:\n    values:\n      - namespace: quality\n  production:\n    values:\n      - namespace: production\nreleases:\n  - name: api\n    namespace: {{ .StateValues.namespace }}\n");
        List<YAMLKeyValue> declarations = PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLKeyValue.class).stream()
                .filter(key -> key.getKeyText().equals("namespace") && HelmfileSchema.ancestors(key).contains("environments")).toList();
        assertSize(2, declarations);
        for (YAMLKeyValue declaration : declarations) assertSize(1, HelmfileUsageSearch.find(declaration, GlobalSearchScope.projectScope(getProject())));
        HelmfileSettings.get(getProject()).select("production");
        for (YAMLKeyValue declaration : declarations) assertSize(1, HelmfileUsageSearch.find(declaration, GlobalSearchScope.projectScope(getProject())));
    }
    public void testRegisteredGutterProviderProducesEditorButtons() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", "templates:\n  shared:\n    chart: example/api\nreleases:\n  - name: api\n    inherit:\n      - template: shared\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var gutters = myFixture.findAllGutters();
        assertTrue(gutters.toString(), gutters.stream().anyMatch(gutter -> String.valueOf(gutter.getTooltipText()).contains("parent Helmfile")));
        assertTrue(gutters.toString(), gutters.stream().anyMatch(gutter -> String.valueOf(gutter.getTooltipText()).contains("inheriting Helmfile")));
    }
    public void testNativeFindUsagesTargetsYamlDeclarationAtCaret() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "values:\n  - names<caret>pace: quality\nreleases:\n  - name: api\n    namespace: {{ .StateValues.namespace }}\n");
        PsiElement target = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.getEditor(),
                com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED | com.intellij.codeInsight.TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED);
        assertNotNull(target);
        assertTrue(new HelmfileFindUsagesHandlerFactory().canFindUsages(target));
    }
}
