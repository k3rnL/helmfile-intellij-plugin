package dev.helmfile.intellij;

import com.intellij.find.actions.*;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.util.PsiNavigateUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.reference.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.*;
import com.intellij.usages.impl.*;
import com.intellij.util.ProcessingContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.YAMLLanguage;
import org.jetbrains.yaml.psi.*;

public class HelmfileUsageRegressionTest extends BasePlatformTestCase {
    private CapturingPopupManager currentPopup;
    private static final String STATE = """
            environments:
              quality:
                values:
                  - namespace: quality
              production:
                values:
                  - namespace: production
            ---
            templates:
              shared:
                chart: example/api
            releases:
              - name: api
                inherit:
                  - template: shared
                namespace: {{ .StateValues.namespace }}
            """;
    @Override protected void tearDown() throws Exception {
        try { HelmfileSettings.get(getProject()).select("default"); } finally { super.tearDown(); }
    }
    private YAMLKeyValue environmentKey(PsiFile file, String environment) {
        return PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLKeyValue.class).stream()
                .filter(key -> key.getKeyText().equals("namespace") && HelmfileSchema.ancestors(key).contains(environment)).findFirst().orElseThrow();
    }
    public void testEnvironmentDeclarationCtrlClickShowsReleaseUsagesInNativePreviewPopup() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", STATE);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        HelmfileSettings.get(getProject()).select("quality");
        YAMLKeyValue declaration = environmentKey(file, "production");
        myFixture.getEditor().getCaretModel().moveToOffset(declaration.getKey().getTextOffset());
        PsiElement[] targets = new HelmfileGotoDeclarationHandler().getGotoDeclarationTargets(declaration.getKey(), declaration.getKey().getTextOffset(), myFixture.getEditor());
        assertSize(1, targets);
        CapturingPopupManager manager = captureNativePopup();
        PsiNavigateUtil.navigate(targets[0]);
        flushNativeSearch();
        assertNotNull(manager.handler);
        assertInstanceOf(manager.view, UsageViewPopup.class);
        assertTrue(manager.handler.getPresentation().getSearchTargetString().contains("Helmfile usages"));
        assertSize(1, manager.usages);
        UsageInfo2UsageAdapter usage = (UsageInfo2UsageAdapter) manager.usages.getFirst();
        assertTrue(usage.getPlainText(), usage.getPlainText().contains(".StateValues.namespace"));
        assertTrue(usage.getNavigationOffset() > file.getText().indexOf("releases:"));
        assertEquals("quality", HelmfileSettings.get(getProject()).environment());
    }
    public void testAmbiguousDeclarationsUseNativeUsageRowsWithSourceAndPosition() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", STATE);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        int offset = file.getText().lastIndexOf("namespace");
        PsiElement[] targets = new HelmfileGotoDeclarationHandler().getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.getEditor());
        assertSize(1, targets);
        CapturingPopupManager manager = captureNativePopup();
        PsiNavigateUtil.navigate(targets[0]);
        flushNativeSearch();
        assertInstanceOf(manager.view, UsageViewPopup.class);
        assertTrue(manager.handler.getPresentation().getSearchTargetString().contains("Helmfile declarations"));
        assertSize(2, manager.usages);
        Set<Integer> lines = new HashSet<>();
        for (Usage usage : manager.usages) {
            UsageInfo2UsageAdapter row = (UsageInfo2UsageAdapter) usage;
            assertEquals(file.getVirtualFile(), row.getFile());
            assertTrue(row.getPlainText(), row.getPlainText().contains("namespace:"));
            lines.add(row.getLine());
        }
        assertSize(2, lines);
        assertTrue(manager.handler.navigateToSingleUsageImmediately());
    }
    public void testForeignKubernetesLikeReferencesCannotBreakUsagesOrDownwardGutter() {
        AtomicInteger resolutions = new AtomicInteger();
        PsiReferenceProvider poison = new PsiReferenceProvider() {
            @Override public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element, @NotNull ProcessingContext context) {
                return element.getContainingFile().getName().equals("template-pod-executor.yaml") ? new PsiReference[]{new FailingReference(element, resolutions)} : PsiReference.EMPTY_ARRAY;
            }
        };
        var registrar = (PsiReferenceRegistrarImpl) ReferenceProvidersRegistry.getInstance().getRegistrar(YAMLLanguage.INSTANCE);
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar.class), poison, PsiReferenceRegistrar.HIGHER_PRIORITY, getTestRootDisposable());
        PsiFile foreign = myFixture.addFileToProject("template-pod-executor.yaml", "apiVersion: v1\nkind: Pod\nmetadata:\n  name: shared\n  namespace: namespace\n");
        YAMLScalar foreignName = PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(foreign), YAMLScalar.class).stream().filter(scalar -> scalar.getTextValue().equals("shared")).findFirst().orElseThrow();
        assertTrue(Arrays.stream(foreignName.getReferences()).anyMatch(reference -> reference instanceof FailingReference));
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", STATE);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        HelmfileSettings.get(getProject()).select("quality");
        YAMLKeyValue declaration = environmentKey(file, "production");
        var handler = new HelmfileFindUsagesHandlerFactory().createFindUsagesHandler(declaration, false);
        List<UsageInfo> usages = new ArrayList<>();
        assertTrue(handler.processElementUsages(declaration, usage -> { usages.add(usage); return true; }, new FindUsagesOptions(GlobalSearchScope.projectScope(getProject()))));
        assertSize(1, usages);
        var gutters = myFixture.findAllGutters();
        assertTrue(gutters.toString(), gutters.stream().anyMatch(gutter -> String.valueOf(gutter.getTooltipText()).contains("inheriting Helmfile")));
        assertTrue(gutters.toString(), gutters.stream().anyMatch(gutter -> String.valueOf(gutter.getTooltipText()).contains("parent Helmfile")));
        assertEquals(0, resolutions.get());
    }
    public void testNativeGoToDeclarationActionOpensUsagePreviewForEnvironmentKey() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", STATE);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        YAMLKeyValue declaration = environmentKey(file, "production");
        myFixture.getEditor().getCaretModel().moveToOffset(declaration.getKey().getTextOffset());
        CapturingPopupManager manager = captureNativePopup();
        invokeNativeGoToDeclaration(file);
        assertNotNull(manager.handler);
        assertInstanceOf(manager.view, UsageViewPopup.class);
        assertSize(1, manager.usages);
    }
    public void testNativeCtrlClickOnRepositoryNameOpensItsChartUsages() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", "repositories:\n  - name: trino\n    url: https://repo.invalid\nreleases:\n  - name: api\n    chart: trino/trino\n  - name: worker\n    chart: trino/worker\n");
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf("name: trino") + "name: ".length());
        CapturingPopupManager manager = captureNativePopup();
        invokeNativeGoToDeclaration(file);
        assertInstanceOf(manager.view, UsageViewPopup.class);
        assertSize(2, manager.usages);
        assertTrue(manager.handler.getPresentation().getSearchTargetString().contains("trino"));
        for (Usage usage : manager.usages) assertTrue(((UsageInfo2UsageAdapter) usage).getPlainText().contains("chart:"));
    }
    public void testNativeGoToDeclarationActionUsesPreviewForAmbiguousEnvironmentFields() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", STATE);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().lastIndexOf("namespace"));
        CapturingPopupManager manager = captureNativePopup();
        invokeNativeGoToDeclaration(file);
        assertInstanceOf(manager.view, UsageViewPopup.class);
        assertTrue(manager.handler.getPresentation().getSearchTargetString().contains("Helmfile declarations"));
        assertSize(2, manager.usages);
    }
    private void invokeNativeGoToDeclaration(PsiFile file) {
        var action = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("GotoDeclaration");
        var data = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, getProject())
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.getEditor())
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, file).build();
        action.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(action, null, "test", data));
        flushNativeSearch();
    }
    private void flushNativeSearch() {
        com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertNotNull(currentPopup.search);
        com.intellij.testFramework.PlatformTestUtil.waitForFuture(currentPopup.search, 10_000);
    }
    public void testDownwardArrowOpensNativeUsagePreview() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", STATE);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var provider = new HelmfileInheritanceLineMarkerProvider();
        List<com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo<?>> markers = new ArrayList<>();
        PsiTreeUtil.processElements(HelmfileValues.yaml(file), element -> { provider.collectNavigationMarkers(element, markers); return true; });
        var down = markers.stream().filter(marker -> marker.getLineMarkerTooltip().contains("inheriting Helmfile")).findFirst().orElseThrow();
        CapturingPopupManager manager = captureNativePopup();
        click(down);
        flushNativeSearch();
        assertInstanceOf(manager.view, UsageViewPopup.class);
        assertSize(1, manager.usages);
        assertTrue(((UsageInfo2UsageAdapter) manager.usages.getFirst()).getPlainText().contains("template: shared"));
    }
    private <T extends PsiElement> void click(com.intellij.codeInsight.daemon.LineMarkerInfo<T> marker) {
        marker.getNavigationHandler().navigate(new java.awt.event.MouseEvent(myFixture.getEditor().getContentComponent(),
                java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 0, 0, 1, false, java.awt.event.MouseEvent.BUTTON1), marker.getElement());
    }
    private CapturingPopupManager captureNativePopup() {
        CapturingPopupManager manager = new CapturingPopupManager();
        currentPopup = manager;
        ServiceContainerUtil.replaceService(getProject(), ShowUsagesManager.class, manager, getTestRootDisposable());
        return manager;
    }
    private final class CapturingPopupManager extends ShowUsagesManager {
        ShowUsagesActionHandler handler;
        UsageViewImpl view;
        final List<Usage> usages = new ArrayList<>();
        Future<Collection<Usage>> search;
        @Override public @NotNull Future<Collection<Usage>> showElementUsagesWithResult(@NotNull ShowUsagesParameters parameters, @NotNull ShowUsagesActionHandler handler, @NotNull UsageViewImpl view) {
            this.handler = handler; this.view = view;
            Disposer.register(getTestRootDisposable(), view);
            search = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(() -> {
                com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(
                        () -> handler.createUsageSearcher().generate(usage -> { usages.add(usage); return true; }),
                        new com.intellij.openapi.progress.EmptyProgressIndicator());
                return usages;
            });
            return search;
        }
    }
    private static final class FailingReference extends PsiReferenceBase<PsiElement> {
        private final AtomicInteger resolutions;
        FailingReference(PsiElement element, AtomicInteger resolutions) { super(element, new TextRange(0, element.getTextLength()), true); this.resolutions = resolutions; }
        @Override public PsiElement resolve() { resolutions.incrementAndGet(); throw new AssertionError("Unrelated Kubernetes reference resolution must never run"); }
    }
}
