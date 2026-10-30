package dev.helmfile.intellij;

import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class HelmfileReleaseGutterTest extends BasePlatformTestCase {
    private PsiFile configure(String name) {
        return myFixture.configureByText("helmfile.yaml.gotmpl", """
                values:
                  - app:
                      name: web
                ---
                templates:
                  service:
                    chart: example/service
                releases:
                  - name: %s
                    inherit:
                      - template: service
                    namespace: {{ .Environment.Name }}
                """.formatted(name));
    }
    private void assertGutter(String tooltip) {
        var gutters = myFixture.findAllGutters();
        assertTrue("Missing gutter '" + tooltip + "': " + gutters, gutters.stream()
                .anyMatch(gutter -> gutter.getTooltipText() != null && gutter.getTooltipText().contains(tooltip)));
    }
    public void testUnquotedTemplateOnlyNameHasNativeRunGutter() {
        configure("{{ .Values.app.name }}");
        assertGutter("Run Helmfile for this release");
    }
    public void testQuotedTemplateOnlyNameHasNativeRunGutter() {
        configure("'{{ .Values.app.name }}'");
        assertGutter("Run Helmfile for this release");
    }
    public void testMixedLiteralAndTemplateNameHasNativeRunGutter() {
        configure("web-{{ .Environment.Name }}");
        assertGutter("Run Helmfile for this release");
    }
    public void testTemplateOnlyNameKeepsBothInheritanceDirections() {
        configure("{{ .Values.app.name }}");
        assertGutter("parent Helmfile template");
        assertGutter("inheriting Helmfile releases");
    }
    public void testTemplateNameCanOpenReleaseDialogFromCaretPosition() {
        PsiFile file = configure("{{ .Values.app.name }}");
        var declaration = HelmfileReleaseTarget.at(file, file.getText().indexOf(".Values.app.name") + 5);
        assertNotNull(declaration);
        assertTrue(HelmfileReleaseTarget.isRelease(declaration));
        HelmfileReleaseDialog dialog = new HelmfileReleaseDialog(declaration, HelmfileOperation.TEMPLATE);
        try {
            assertEquals("", dialog.command().release());
            assertEquals(HelmfileOperation.TEMPLATE, dialog.command().operation());
        } finally { Disposer.dispose(dialog.getDisposable()); }
    }
    public void testTemplateOnlyNameAtEndOfFileIsActionable() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "releases:\n  - name: {{ .Values.app.name }}");
        assertGutter("Run Helmfile for this release");
        assertNotNull(HelmfileReleaseTarget.at(file, file.getText().indexOf(".Values")));
    }
    public void testStandaloneTemplateDoesNotPickPreviousRelease() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                releases:
                  - name: web
                    chart: example/service
                {{ .Values.extraReleases | toYaml }}
                """);
        assertNull(HelmfileReleaseTarget.at(file, file.getText().indexOf(".Values")));
    }
    public void testNativeRunGutterRenderersRemainEqualAcrossAnalysisPasses() {
        PsiFile file = configure("api");
        var provider = new com.intellij.execution.lineMarker.RunLineMarkerProvider();
        var keys = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), org.jetbrains.yaml.psi.YAMLKeyValue.class);
        int count = 0;
        for (var key : keys) {
            if (!(key.getKeyText().equals("releases") || HelmfileReleaseTarget.isRelease(key))) continue;
            var leaf = com.intellij.psi.util.PsiTreeUtil.getDeepestFirst(key.getKey());
            var first = provider.getLineMarkerInfo(leaf);
            var second = provider.getLineMarkerInfo(leaf);
            assertNotNull(first); assertNotNull(second);
            assertEquals("The IDE must recognize an unchanged run marker on " + key.getKeyText(), first.createGutterRenderer(), second.createGutterRenderer());
            assertEquals(first.createGutterRenderer().hashCode(), second.createGutterRenderer().hashCode());
            count++;
        }
        assertEquals(2, count);
    }
    public void testRepeatedAnalysisKeepsExactlyOneRunAndInheritanceGutterPerDeclaration() {
        configure("api");
        for (int pass = 0; pass < 3; pass++) {
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(getProject()).restart();
            var gutters = myFixture.findAllGutters();
            for (String tooltip : java.util.List.of("Run Helmfile for this release", "Diff all Helmfile releases",
                    "Go to parent Helmfile template", "Go to inheriting Helmfile releases or templates")) {
                assertEquals("Pass " + pass + ": " + tooltip, 1, gutters.stream().filter(gutter -> tooltip.equals(gutter.getTooltipText())).count());
            }
        }
    }
    public void testCompactReleaseNameOpensDialogWithStateNamespace() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "namespace: shared\nreleases: [name: web]\n");
        var name = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), org.jetbrains.yaml.psi.YAMLKeyValue.class).stream()
                .filter(key -> key.getKeyText().equals("name")).findFirst().orElseThrow();
        assertNull(name.getParentMapping());
        HelmfileReleaseDialog dialog = new HelmfileReleaseDialog(name, HelmfileOperation.TEMPLATE);
        try {
            assertEquals("web", dialog.command().release());
            assertEquals("shared", dialog.command().namespace());
        } finally { Disposer.dispose(dialog.getDisposable()); }
    }
}
