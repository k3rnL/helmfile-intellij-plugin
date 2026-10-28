package dev.helmfile.intellij;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.pom.PomTargetPsiElement;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.jetbrains.yaml.psi.YAMLScalar;

public class HelmfileChartNavigationTest extends BasePlatformTestCase {
    private Path directory;
    private Path executable;
    @Override protected void setUp() throws Exception {
        super.setUp();
        resetChartLibraries();
        directory = Files.createTempDirectory("helm chart navigation ");
        executable = Files.writeString(directory.resolve("fake helm"), """
                #!/bin/sh
                printf 'call\\n' >> '%s/calls'
                dest=''
                while [ "$#" -gt 0 ]; do
                  case "$1" in --destination) shift; dest="$1";; esac
                  shift
                done
                mkdir -p "$dest/sample/templates"
                printf 'apiVersion: v2\\nname: sample\\nversion: 1.2.3\\n' > "$dest/sample/Chart.yaml"
                printf 'replicaCount: 2\\n' > "$dest/sample/values.yaml"
                printf 'kind: ConfigMap\\n' > "$dest/sample/templates/configmap.yaml"
                """.formatted(directory));
        assertTrue(executable.toFile().setExecutable(true));
        HelmfileSettings.get(getProject()).helmExecutable(executable.toString());
    }
    @Override protected void tearDown() throws Exception {
        try {
            resetChartLibraries();
            HelmfileSettings.get(getProject()).loadState(new HelmfileSettings.State());
            if (directory != null) try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        } finally { super.tearDown(); }
    }
    private void resetChartLibraries() {
        com.intellij.openapi.application.WriteAction.run(() -> com.intellij.openapi.roots.ex.ProjectRootManagerEx.getInstanceEx(getProject())
                .makeRootsChange(() -> HelmfileChartSourceService.get(getProject()).loadState(new HelmfileChartSourceService.State()),
                        com.intellij.openapi.project.RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED));
    }
    private PsiFile configure(String fields) {
        return myFixture.configureByText("helmfile.yaml.gotmpl", """
                repositories:
                  - name: bigdata-helm-charts
                    url: https://repo.invalid/charts
                releases:
                  - name: app
                    %s
                """.formatted(fields));
    }
    private YAMLScalar chart(PsiFile file) {
        return PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLScalar.class).stream()
                .filter(scalar -> HelmfileChartNavigation.reference(scalar) != null).findFirst().orElseThrow();
    }
    private HelmfileChartNavigation.Request pull(String version) {
        return new HelmfileChartNavigation.Request("bigdata-helm-charts/sample", version, directory.toString(), executable.toString(),
                new HelmfileRepositories.Access("bigdata-helm-charts", "https://repo.invalid", false, "", "", "", "", "", false, false, false), false, false, "", "");
    }
    private Path fetch(HelmfileChartNavigation.Request request) throws Exception {
        var service = HelmfileChartSourceService.get(getProject());
        return com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(() -> service.fetch(request, directory.resolve("cache"), new EmptyProgressIndicator()))
                .get(10, TimeUnit.SECONDS);
    }
    public void testRepositoryPrefixAndChartNameHaveDistinctNativeTargets() {
        PsiFile file = configure("chart: 'bigdata-helm-charts/bgdmar-utils'\n    version: 1.2.3");
        YAMLScalar scalar = chart(file);
        var repository = HelmfileRepositories.reference(scalar);
        var chart = HelmfileChartNavigation.reference(scalar);
        assertEquals("bigdata-helm-charts", repository.getRangeInElement().substring(scalar.getText()));
        assertEquals("bgdmar-utils", chart.getRangeInElement().substring(scalar.getText()));
        int start = scalar.getTextOffset();
        var handler = new HelmfileGotoDeclarationHandler();
        assertEquals("bigdata-helm-charts", handler.getGotoDeclarationTargets(file.findElementAt(start + 2), start + 2, myFixture.getEditor())[0].getText());
        int name = start + scalar.getText().indexOf("bgdmar-utils") + 2;
        assertInstanceOf(handler.getGotoDeclarationTargets(file.findElementAt(name), name, myFixture.getEditor())[0], PomTargetPsiElement.class);
        assertSize(2, HelmfileReferences.forScalar(scalar));
        assertFalse("Ctrl-hover must not run Helm", Files.exists(directory.resolve("calls")));
    }
    public void testLocalChartNavigatesDirectlyToChartYaml() {
        PsiFile definition = myFixture.addFileToProject("charts/sample/Chart.yaml", "apiVersion: v2\nname: sample\nversion: 1.0.0\n");
        PsiFile file = configure("chart: charts/sample");
        var reference = HelmfileChartNavigation.reference(chart(file));
        assertEquals(definition, reference.resolve());
        assertFalse(Files.exists(directory.resolve("calls")));
    }
    public void testPackagedLocalChartKeepsExistingFileNavigation() {
        PsiFile packageFile = myFixture.addFileToProject("charts/sample.tgz", "packaged chart placeholder");
        PsiFile file = configure("chart: charts/sample.tgz");
        YAMLScalar scalar = PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLScalar.class).stream()
                .filter(value -> value.getTextValue().equals("charts/sample.tgz")).findFirst().orElseThrow();
        assertNull(HelmfileChartNavigation.reference(scalar));
        var references = HelmfileReferences.forScalar(scalar);
        assertInstanceOf(references[0], HelmfileReferences.PathReference.class);
        assertEquals(packageFile, ((PsiPolyVariantReference) references[0]).multiResolve(false)[0].getElement());
    }
    public void testReleaseVersionResolvesStateValuesAndDefaultPipeline() {
        PsiFile file = configure("chart: bigdata-helm-charts/bgdmar-utils\n    version: {{ .StateValues.versions.utils | default \"1.0.0\" }}");
        myFixture.configureByText("helmfile.yaml.gotmpl", "values:\n  - versions:\n      utils: 2.3.4\n" + file.getText());
        var request = HelmfileChartNavigation.request(chart(myFixture.getFile()));
        assertEquals("2.3.4", request.version());
        assertNull(request.problem());
    }
    public void testVersionComesFromInheritedTemplateAndExceptIsHonored() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                repositories:
                  - name: example
                    url: https://repo.invalid
                templates:
                  common:
                    version: ~1.2
                releases:
                  - name: app
                    inherit:
                      - template: common
                    chart: example/sample
                """);
        assertEquals("~1.2", HelmfileChartNavigation.request(chart(file)).version());
        file = myFixture.configureByText("helmfile.yaml.gotmpl", file.getText().replace("- template: common", "- template: common\n        except: [version]"));
        assertEquals("", HelmfileChartNavigation.request(chart(file)).version());
    }
    public void testCompactChartEntryCanNavigateWithoutAParentMapping() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                repositories:
                  - name: example
                    url: https://repo.invalid
                releases: [chart: example/sample]
                """);
        YAMLScalar scalar = chart(file);
        var key = PsiTreeUtil.getParentOfType(scalar, org.jetbrains.yaml.psi.YAMLKeyValue.class);
        assertNotNull(key);
        assertNull(key.getParentMapping());
        var request = HelmfileChartNavigation.request(scalar);
        assertEquals("example/sample", request.chart());
        assertEquals("", request.version());
        assertInstanceOf(HelmfileChartNavigation.reference(scalar).resolve(), PomTargetPsiElement.class);
        assertFalse("Resolving a reference must not download a chart", Files.exists(directory.resolve("calls")));
    }
    public void testCompactTemplateInheritanceResolvesVersionAndVerification() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                repositories:
                  - name: example
                    url: https://repo.invalid
                templates:
                  common:
                    version: ~1.2
                    verify: true
                releases:
                  - name: app
                    inherit: [template: common]
                    chart: example/sample
                """);
        var request = HelmfileChartNavigation.request(chart(file));
        assertEquals("~1.2", request.version());
        assertTrue(request.verify());
        assertFalse(Files.exists(directory.resolve("calls")));
    }
    public void testUnresolvedVersionDoesNotSilentlyOpenLatestChart() {
        PsiFile file = configure("chart: bigdata-helm-charts/sample\n    version: {{ requiredEnv \"CHART_VERSION\" }}");
        var request = HelmfileChartNavigation.request(chart(file));
        assertNotNull(request.problem());
        assertFalse("Selecting an environment cannot evaluate arbitrary Go functions", request.needsEnvironment());
    }
    public void testNativeEnvironmentChooserRecomputesVersionAndRepositoryFromSelectedEnvironment() {
        myFixture.addFileToProject("base.yaml", """
                environments:
                  staging:
                    values:
                      - versions:
                          utils: 1.2.3
                        repository: https://staging.invalid/charts
                  production:
                    values:
                      - versions:
                          utils: 2.3.4
                        repository: https://production.invalid/charts
                """);
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                bases:
                  - base.yaml
                ---
                repositories:
                  - name: example
                    url: '{{ .StateValues.repository }}'
                releases:
                  - name: app
                    chart: example/sample
                    version: {{ .StateValues.versions.utils | quote }}
                """);
        var pointer = SmartPointerManager.createPointer(chart(file));
        var original = HelmfileChartNavigation.currentRequest(pointer);
        assertNotNull(original.problem());
        assertTrue(original.needsEnvironment());
        var selected = new java.util.concurrent.atomic.AtomicReference<HelmfileChartNavigation.Request>();
        var popup = SelectEnvironmentAction.chooser(file, "Choose environment to open Helm chart",
                () -> selected.set(HelmfileChartNavigation.currentRequest(pointer)));
        try {
            var list = com.intellij.util.ui.UIUtil.findComponentOfType(popup.getContent(), javax.swing.JList.class);
            assertNotNull(list);
            assertEquals(List.of("default", "production", "staging"), java.util.stream.IntStream.range(0, list.getModel().getSize())
                    .mapToObj(index -> list.getModel().getElementAt(index)).toList());
            list.setSelectedValue("production", true);
            popup.closeOk(null);
            assertNotNull("Selecting an environment must retry with fresh state values", selected.get());
            assertEquals("production", HelmfileSettings.get(getProject()).environment());
            assertEquals("2.3.4", selected.get().version());
            assertEquals("https://production.invalid/charts", selected.get().access().url());
            assertNull(selected.get().problem());
            assertFalse(selected.get().needsEnvironment());
            assertFalse("Selection alone must not download chart sources", Files.exists(directory.resolve("calls")));
        } finally { if (!popup.isDisposed()) popup.cancel(); }
    }
    public void testCancellingEnvironmentChooserKeepsEnvironmentAndDoesNotRetry() {
        PsiFile file = configure("chart: bigdata-helm-charts/sample\n    version: {{ .StateValues.version }}");
        var retried = new java.util.concurrent.atomic.AtomicBoolean();
        var popup = SelectEnvironmentAction.chooser(file, "Choose environment to open Helm chart", () -> retried.set(true));
        popup.cancel();
        assertFalse(retried.get());
        assertEquals("default", HelmfileSettings.get(getProject()).environment());
        assertFalse(Files.exists(directory.resolve("calls")));
    }
    public void testChartReferencesApplyToTemplatesAndDependenciesButNotInlineValues() {
        PsiFile file = myFixture.configureByText("helmfile.yaml.gotmpl", """
                repositories:
                  - name: example
                    url: https://repo.invalid
                templates:
                  common:
                    chart: example/sample
                    dependencies:
                      - chart: example/utils
                        version: 1.0.0
                    values:
                      - chart: example/not-a-reference
                """);
        var references = PsiTreeUtil.findChildrenOfType(HelmfileValues.yaml(file), YAMLScalar.class).stream()
                .map(HelmfileChartNavigation::reference).filter(Objects::nonNull).toList();
        assertSize(2, references);
        assertTrue(references.stream().allMatch(reference -> reference.resolve() instanceof PomTargetPsiElement));
    }
    public void testPullUsesNativeHelmVersionConstraintsAndKeepsArgumentsSeparate() {
        var line = pull(">=1.0.0 <2.0.0").commandLine(directory.resolve("destination with spaces"));
        assertEquals(List.of("pull", "--untar", "--destination", directory.resolve("destination with spaces").toString(),
                "--untardir", ".", "--version", ">=1.0.0 <2.0.0", "--repo", "https://repo.invalid", "--", "sample"), line.getParametersList().getList());
        assertEquals(directory.toFile(), line.getWorkDirectory());
    }
    public void testOciAliasAndDirectReferenceUseSamePullTarget() {
        var access = new HelmfileRepositories.Access("registry", "ghcr.io/example/charts", true, "", "", "", "", "", false, false, false);
        for (String chart : List.of("registry/sample", "oci://ghcr.io/example/charts/sample")) {
            var request = new HelmfileChartNavigation.Request(chart, "1.2.3", directory.toString(), executable.toString(), access, false, false, "", "");
            var args = request.commandLine(directory.resolve("sources")).getParametersList().getList();
            assertEquals("oci://ghcr.io/example/charts/sample", args.getLast());
            assertFalse(args.contains("--repo"));
        }
    }
    public void testDownloadedChartHasBrowsableReadOnlyContentsAndReusesCache() throws Exception {
        Path archive = fetch(pull("1.2.3"));
        assertEquals(archive, fetch(pull("1.2.3")));
        assertEquals(List.of("call"), Files.readAllLines(directory.resolve("calls")));
        var root = JarFileSystem.getInstance().refreshAndFindFileByPath(archive + "!/");
        assertNotNull(root);
        assertNotNull(root.findChild("Chart.yaml"));
        assertNotNull(root.findChild("values.yaml"));
        assertNotNull(root.findFileByRelativePath("templates/configmap.yaml"));
        assertFalse(root.findChild("values.yaml").isWritable());
        var index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject());
        assertFalse(index.isInLibrarySource(root.findChild("values.yaml")));
        var service = HelmfileChartSourceService.get(getProject());
        service.attach(HelmfileChartSourceService.fingerprint(pull("1.2.3")), root.findChild("Chart.yaml"));
        var library = new HelmfileChartLibraryProvider().getAdditionalProjectLibraries(getProject()).iterator().next();
        assertTrue(library.isShowInExternalLibrariesNode());
        assertTrue(library.getSourceRoots().contains(root));
        assertEquals("Helm chart sample (1.2.3)", ((com.intellij.navigation.ItemPresentation) library).getPresentableText());
        assertSize(1, service.getState().charts);
        assertTrue("Downloaded files must be registered as library sources", index.isInLibrarySource(root.findChild("values.yaml")));
        assertChartTreeContainsSources(root);
    }
    private void assertChartTreeContainsSources(com.intellij.openapi.vfs.VirtualFile root) {
        var external = new com.intellij.ide.projectView.impl.nodes.ExternalLibrariesNode(getProject(), com.intellij.ide.projectView.ViewSettings.DEFAULT);
        var library = external.getChildren().stream().filter(node -> node.getValue() instanceof HelmfileChartLibraryProvider.ChartLibrary chart && chart.getSourceRoots().contains(root)).findFirst().orElseThrow();
        var folder = library.getChildren().stream().filter(node -> node.getValue() instanceof PsiDirectory dir && dir.getVirtualFile().equals(root)).findFirst().orElseThrow();
        var children = folder.getChildren();
        assertTrue("Native Project tree must show values.yaml", children.stream().anyMatch(node -> node.getValue() instanceof PsiFile file && file.getName().equals("values.yaml")));
        var templates = children.stream().filter(node -> node.getValue() instanceof PsiDirectory dir && dir.getName().equals("templates")).findFirst().orElseThrow();
        assertTrue("Native Project tree must expand templates", templates.getChildren().stream().anyMatch(node -> node.getValue() instanceof PsiFile file && file.getName().equals("configmap.yaml")));
    }
    public void testDifferentChartVersionsHaveSeparateCachedSources() throws Exception {
        assertFalse(fetch(pull("1.2.3")).equals(fetch(pull("1.2.4"))));
        assertSize(2, Files.readAllLines(directory.resolve("calls")));
    }
    public void testNativeBackgroundOpenAttachesChartFolderAndOpensChartYaml() {
        var service = HelmfileChartSourceService.get(getProject());
        // Reproduce an already-open Project view, whose file index predates the download.
        assertTrue(new HelmfileChartLibraryProvider().getAdditionalProjectLibraries(getProject()).isEmpty());
        com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject()).isInLibrarySource(myFixture.getTempDirFixture().getFile(""));
        var started = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(() -> service.open(pull("1.2.3")));
        com.intellij.testFramework.PlatformTestUtil.waitForFuture(started, 10_000);
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Chart.yaml was not opened", () ->
                Arrays.stream(com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject()).getOpenFiles())
                        .anyMatch(file -> file.getName().equals("Chart.yaml") && file.getFileSystem() instanceof JarFileSystem), 10_000);
        assertFalse(service.libraries().isEmpty());
        var root = service.libraries().iterator().next().getSourceRoots().iterator().next();
        assertTrue(com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject()).isInLibrarySource(root.findChild("values.yaml")));
        assertChartTreeContainsSources(root);
    }
    public void testCachedChartRestorationRegistersBrowsableLibrarySources() {
        testNativeBackgroundOpenAttachesChartFolderAndOpensChartYaml();
        var service = HelmfileChartSourceService.get(getProject());
        var root = service.libraries().iterator().next().getSourceRoots().iterator().next();
        var state = service.getState();
        resetChartLibraries();
        assertFalse(com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject()).isInLibrarySource(root.findChild("values.yaml")));
        service.loadState(state);
        var started = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(service::libraries);
        com.intellij.testFramework.PlatformTestUtil.waitForFuture(started, 10_000);
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Cached chart sources were not registered", () ->
                com.intellij.openapi.roots.ProjectFileIndex.getInstance(getProject()).isInLibrarySource(root.findChild("values.yaml")), 10_000);
        assertChartTreeContainsSources(root);
        try { assertSize(1, Files.readAllLines(directory.resolve("calls"))); }
        catch (IOException exception) { throw new AssertionError(exception); }
    }
    public void testFailedDownloadNeverCreatesUsableCacheOrLeavesTemporaryFiles() throws Exception {
        Files.writeString(executable, "#!/bin/sh\nexit 7\n");
        var failure = org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException.class, () -> fetch(pull("1.0.0")));
        assertInstanceOf(failure.getCause(), IOException.class);
        assertTrue(failure.getCause().getMessage().contains("code 7"));
        assertTrue(failure.getCause().getMessage().contains("no output"));
        try (var files = Files.list(directory.resolve("cache"))) { assertEquals(0L, files.count()); }
    }
    public void testFailedDownloadPreservesHelmStderrAndStdout() throws Exception {
        Files.writeString(executable, "#!/bin/sh\nprintf 'Checking repository\\n'\nprintf 'Warning: retrying\\nError: chart sample version 1.0.0 not found\\n' >&2\nexit 1\n");
        var failure = org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException.class, () -> fetch(pull("1.0.0")));
        String message = failure.getCause().getMessage();
        assertTrue(message, message.contains("Error: chart sample version 1.0.0 not found"));
        assertTrue(message, message.contains("Checking repository"));
        assertTrue(message, message.contains("code 1"));
    }
    public void testFailedDownloadUsesStdoutErrorWhenStderrIsEmptyOrOnlyWarnings() throws Exception {
        for (String warning : List.of("", "printf 'Warning: retrying\\n' >&2\n")) {
            Files.writeString(executable, "#!/bin/sh\n" + warning + "printf 'Error: registry returned 403 Forbidden\\n'\nexit 1\n");
            var failure = org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException.class, () -> fetch(pull("1.0.0")));
            assertTrue(failure.getCause().getMessage(), failure.getCause().getMessage().lines().findFirst().orElseThrow().contains("403 Forbidden"));
        }
    }
    public void testFailedStartShowsTheExecutableErrorAndSettingsLocation() throws Exception {
        Files.delete(executable);
        var failure = org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException.class, () -> fetch(pull("1.0.0")));
        assertInstanceOf(failure.getCause(), IOException.class);
        assertTrue(failure.getCause().getMessage().contains("fake helm"));
        assertTrue(failure.getCause().getMessage().contains("Settings | Tools | Helmfile"));
    }
    public void testFailureNotificationOpensNativeDetailsWithContextAndRedactedOutput() throws Exception {
        String password = "top secret-42";
        String encoded = java.net.URLEncoder.encode(password, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String basic = Base64.getEncoder().encodeToString(("alice:" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path errors = Files.writeString(directory.resolve("download-error.txt"), "Warning: retrying\nError: 401 Unauthorized for https://alice:" + encoded
                + "@repo.invalid/charts?private_token=query-secret\nAuthorization: Bearer header-secret   \nBasic value: "
                + basic + "\nPassword: " + password + "\nPassword with terminal control: top\u0000 secret-42\n\"access_token\": \"json-secret\"\n");
        Files.writeString(executable, "#!/bin/sh\nprintf 'Repository lookup started\\n'\ncat '"
                + errors.toString().replace("'", "'\\''") + "' >&2\nexit 1\n");
        var access = new HelmfileRepositories.Access("example", "https://alice:" + encoded + "@repo.invalid/charts?private_token=query-secret",
                false, "alice", password, "", "", "", false, false, false);
        var request = new HelmfileChartNavigation.Request("example/sample", "2.7.4", directory.toString(), executable.toString(), access,
                false, false, "", "");
        HelmfileSettings.get(getProject()).select("staging");
        List<com.intellij.notification.Notification> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(com.intellij.notification.Notifications.TOPIC,
                new com.intellij.notification.Notifications() {
                    @Override public void notify(com.intellij.notification.Notification notification) { notifications.add(notification); }
                });
        var started = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().submit(() -> HelmfileChartSourceService.get(getProject()).open(request));
        com.intellij.testFramework.PlatformTestUtil.waitForFuture(started, 10_000);
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Missing chart failure notification", () -> !notifications.isEmpty(), 10_000);
        var notification = notifications.getFirst();
        assertTrue(notification.getContent(), notification.getContent().contains("401 Unauthorized"));
        assertTrue(notification.getContent().contains("example/sample"));
        assertTrue(notification.getContent().contains("2.7.4"));
        assertTrue(notification.getContent().contains("staging"));
        var action = (com.intellij.notification.NotificationAction) notification.getActions().getFirst();
        assertEquals("Show Details", action.getTemplatePresentation().getText());
        var event = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(action,
                com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(getProject()), action.getTemplatePresentation().clone(),
                "test", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
        // The IDE skips showing Run content in unit-test mode; capture that public service call.
        var shown = new java.util.concurrent.atomic.AtomicReference<com.intellij.execution.ui.RunContentDescriptor>();
        var delegate = com.intellij.execution.ui.RunContentManager.getInstance(getProject());
        var capturing = (com.intellij.execution.ui.RunContentManager) java.lang.reflect.Proxy.newProxyInstance(
                com.intellij.execution.ui.RunContentManager.class.getClassLoader(), new Class<?>[]{com.intellij.execution.ui.RunContentManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("showRunContent")) {
                        assertSame(com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(), args[0]);
                        shown.set((com.intellij.execution.ui.RunContentDescriptor) args[1]);
                        com.intellij.openapi.util.Disposer.register(getTestRootDisposable(), shown.get());
                        return null;
                    }
                    try { return method.invoke(delegate, args); }
                    catch (java.lang.reflect.InvocationTargetException exception) { throw exception.getCause(); }
                });
        com.intellij.testFramework.ServiceContainerUtil.replaceService(getProject(), com.intellij.execution.ui.RunContentManager.class, capturing, getTestRootDisposable());
        action.actionPerformed(event, notification);
        var descriptor = shown.get();
        assertNotNull("Show Details must open the native Run console", descriptor);
        assertEquals("Cannot open Helm chart", descriptor.getDisplayName());
        try {
            var console = (com.intellij.execution.impl.ConsoleViewImpl) descriptor.getExecutionConsole();
            console.flushDeferredText();
            String details = console.getEditor().getDocument().getText();
            assertTrue(details, details.contains("Chart: example/sample"));
            assertTrue(details.contains("Environment: staging"));
            assertTrue(details.contains("Version: 2.7.4"));
            assertTrue(details.contains("Repository lookup started"));
            assertTrue(details.contains("Warning: retrying"));
            assertTrue(details.contains("401 Unauthorized"));
            for (String secret : List.of(password, encoded, basic, "query-secret", "header-secret", "json-secret", "alice:")) {
                assertFalse(details, details.contains(secret));
                assertFalse(notification.getContent(), notification.getContent().contains(secret));
            }
        } finally { com.intellij.openapi.util.Disposer.dispose(descriptor); }
    }
    public void testLargeHelmFailureRetainsTheFinalErrorAndMarksTruncatedOutput() {
        var output = new com.intellij.execution.process.ProcessOutput();
        output.appendStderr("Download started\n" + "Verbose progress line\n".repeat(4000) + "Error: connection reset while downloading sample\n");
        String message = HelmfileDownloadDiagnostics.processFailure("Helm exited with code 1", output, pull("1.0.0").access());
        assertTrue(message, message.lines().findFirst().orElseThrow().contains("connection reset"));
        assertTrue(message.contains("Download started"));
        assertTrue(message.contains("Output truncated"));
        assertTrue(message.length() < 35_000);
    }
    public void testCancelledDownloadNeverStartsHelm() {
        var indicator = new EmptyProgressIndicator(); indicator.cancel();
        assertThrows(ProcessCanceledException.class, () -> HelmfileChartSourceService.get(getProject()).fetch(pull("1.0.0"), directory.resolve("cache"), indicator));
        assertFalse(Files.exists(directory.resolve("calls")));
    }
}
