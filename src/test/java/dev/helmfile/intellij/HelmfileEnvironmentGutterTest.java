package dev.helmfile.intellij;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.icons.AllIcons;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.*;

public class HelmfileEnvironmentGutterTest extends BasePlatformTestCase {
    private static final String STATE = """
            environments:
              default: {}
              quality:
                values:
                  - namespace: quality
              'prod-eu':
                values:
                  - namespace: production
            ---
            releases:
              - name: api
                namespace: {{ .StateValues.namespace }}
            """;
    @Override protected void tearDown() throws Exception {
        try { HelmfileSettings.get(getProject()).select("default"); } finally { super.tearDown(); }
    }
    private List<LineMarkerInfo<?>> markers(PsiFile file) {
        var provider = new HelmfileEnvironmentLineMarkerProvider();
        List<LineMarkerInfo<?>> markers = new ArrayList<>();
        PsiTreeUtil.processElements(HelmfileValues.yaml(file), element -> {
            var marker = provider.getLineMarkerInfo(element);
            if (marker != null) markers.add(marker);
            return true;
        });
        return markers;
    }
    private <T extends PsiElement> void click(LineMarkerInfo<T> marker) {
        assertNotNull(marker.getNavigationHandler());
        marker.getNavigationHandler().navigate(new java.awt.event.MouseEvent(myFixture.getEditor().getContentComponent(),
                java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 0, 0, 1, false, java.awt.event.MouseEvent.BUTTON1), marker.getElement());
    }
    public void testEachEnvironmentHasOneLeafAnchoredButtonAndCurrentEnvironmentIsMarked() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", STATE);
        var markers = markers(file);
        assertSize(3, markers);
        assertTrue(markers.stream().allMatch(marker -> marker.getElement().getFirstChild() == null));
        var current = markers.stream().filter(marker -> marker.getLineMarkerTooltip().equals("Current Helmfile environment: default")).findFirst().orElseThrow();
        assertSame(AllIcons.General.InspectionsOK, current.getIcon());
        assertEquals(2, markers.stream().filter(marker -> marker.getIcon() == AllIcons.Actions.Execute).count());
    }
    public void testClickSwitchesEnvironmentAndInvalidatesResolvedValuesImmediately() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", STATE);
        HelmfileSettings.get(getProject()).select("quality");
        assertEquals("quality", HelmfileValues.model(file).definitions().get("namespace").preview());
        var production = markers(file).stream().filter(marker -> marker.getLineMarkerTooltip().equals("Switch Helmfile environment to prod-eu")).findFirst().orElseThrow();
        click(production);
        assertEquals("prod-eu", HelmfileSettings.get(getProject()).environment());
        assertEquals("production", HelmfileValues.model(file).definitions().get("namespace").preview());
        assertTrue(markers(file).stream().anyMatch(marker -> marker.getLineMarkerTooltip().equals("Current Helmfile environment: prod-eu")
                && marker.getIcon() == AllIcons.General.InspectionsOK));
    }
    public void testCurrentEnvironmentClickDoesNotInvalidateTheModelAgain() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", STATE);
        var current = markers(file).stream().filter(marker -> marker.getLineMarkerTooltip().startsWith("Current")).findFirst().orElseThrow();
        long before = HelmfileSettings.get(getProject()).getModificationCount();
        click(current);
        assertEquals(before, HelmfileSettings.get(getProject()).getModificationCount());
    }
    public void testNestedChartValuesAndOrdinaryYamlDoNotGetEnvironmentButtons() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "releases:\n  - name: api\n    values:\n      - environments:\n          quality: {}\n");
        assertEmpty(markers(file));
        file = myFixture.configureByText("deployment.yaml", "environments:\n  quality: {}\n");
        assertEmpty(markers(file));
    }
    public void testRegisteredProviderShowsEnvironmentButtonsInTheEditor() {
        myFixture.configureByText("helmfile.yaml.gotmpl", STATE);
        var gutters = myFixture.findAllGutters();
        assertEquals(3, gutters.stream().filter(gutter -> String.valueOf(gutter.getTooltipText()).contains("Helmfile environment")).count());
    }
    public void testEnvironmentButtonsAlsoWorkInIncludedPlainYamlBases() {
        PsiFile base = myFixture.addFileToProject("config/environments.yaml", STATE.substring(0, STATE.indexOf("---")));
        myFixture.addFileToProject("helmfile.yaml.gotmpl", "bases: [config/environments.yaml]\nreleases: []\n");
        myFixture.configureFromExistingVirtualFile(base.getVirtualFile());
        assertSize(3, markers(base));
        click(markers(base).stream().filter(marker -> marker.getLineMarkerTooltip().endsWith("quality")).findFirst().orElseThrow());
        assertEquals("quality", HelmfileSettings.get(getProject()).environment());
        PsiFile unrelated = myFixture.addFileToProject("unrelated.yaml", "environments:\n  quality: {}\n");
        assertEmpty(markers(unrelated));
    }
    public void testEnvironmentCanBeSwitchedDuringIndexing() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", STATE);
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertTrue(com.intellij.openapi.project.DumbService.isDumb(getProject()));
            click(markers(file).stream().filter(marker -> marker.getLineMarkerTooltip().endsWith("quality")).findFirst().orElseThrow());
            assertEquals("quality", HelmfileSettings.get(getProject()).environment());
        });
    }
}
