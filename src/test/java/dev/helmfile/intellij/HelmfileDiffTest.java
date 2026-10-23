package dev.helmfile.intellij;

import com.intellij.diff.*;
import com.intellij.diff.chains.DiffRequestChain;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.merge.MergeRequest;
import com.intellij.diff.requests.*;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.execution.process.*;
import com.intellij.notification.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.testFramework.ServiceContainerUtil;
import java.nio.file.*;
import java.util.*;
import java.awt.Window;
import org.jetbrains.yaml.YAMLFileType;

public class HelmfileDiffTest extends BasePlatformTestCase {
    private Path directory, state, executable;
    private final List<DiffRequestChain> shown = new ArrayList<>();
    private final List<Notification> notifications = new ArrayList<>();
    static final String MODIFIED = """
            staging, api, Deployment (apps/v1) has changed:
              # Source: service/templates/deployment.yaml
              apiVersion: apps/v1
              kind: Deployment
              metadata:
                name: api
              spec:
            -   replicas: 1
            +   replicas: 3
            """;
    @Override protected void setUp() throws Exception {
        super.setUp();
        directory = Files.createTempDirectory("helmfile diff ");
        state = Files.writeString(directory.resolve("helmfile.yaml"), "releases: []\n");
        executable = directory.resolve("fake helmfile");
        HelmfileSettings.get(getProject()).configure(executable.toString(), "prod.*");
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), DiffManager.class, new DiffManager() {
            @Override public void showDiff(Project project, DiffRequest request) { fail("Expected native file navigation chain"); }
            @Override public void showDiff(Project project, DiffRequest request, DiffDialogHints hints) { fail("Expected native file navigation chain"); }
            @Override public void showDiff(Project project, DiffRequestChain chain, DiffDialogHints hints) { shown.add(chain); }
            @Override public DiffRequestPanel createRequestPanel(Project project, Disposable owner, Window window) { throw new UnsupportedOperationException(); }
            @Override public void showMerge(Project project, MergeRequest request) { fail("Diff must not modify manifests"); }
        }, getTestRootDisposable());
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC, new Notifications() {
            @Override public void notify(Notification notification) { notifications.add(notification); }
        });
    }
    @Override protected void tearDown() throws Exception {
        try {
            HelmfileSettings.get(getProject()).loadState(new HelmfileSettings.State());
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        } finally { super.tearDown(); }
    }
    private HelmfileCommand command(String release, String environment) {
        return new HelmfileCommand(state.toString(), release, "", environment, HelmfileOperation.DIFF);
    }
    private void binary(String output, String error, int exitCode) throws Exception {
        Path stdout = Files.writeString(directory.resolve("stdout"), output);
        Path stderr = Files.writeString(directory.resolve("stderr"), error);
        Files.writeString(executable, "#!/bin/sh\ncat '" + stdout + "'\ncat '" + stderr + "' >&2\nexit " + exitCode + "\n");
        assertTrue(executable.toFile().setExecutable(true));
    }
    private ProcessHandler run(HelmfileCommand command) throws Exception {
        ProcessHandler handler = HelmfileExecution.start(getProject(), command, Map.of(), true);
        handler.startNotify();
        PlatformTestUtil.waitWithEventsDispatching("Diff process did not finish", handler::isProcessTerminated, 10);
        return handler;
    }
    private SimpleDiffRequest request(com.intellij.diff.chains.DiffRequestProducer producer) throws Exception {
        return (SimpleDiffRequest) producer.process(new UserDataHolderBase(), new EmptyProgressIndicator());
    }
    private String side(SimpleDiffRequest request, int side) { return ((DocumentContent) request.getContents().get(side)).getDocument().getText(); }

    public void testFullContextReconstructsDeployedAndDesiredYaml() {
        var result = HelmfileDiffOutput.parse(MODIFIED);
        assertSize(1, result.resources());
        assertFalse(result.partial());
        var resource = result.resources().getFirst();
        assertEquals("staging/Deployment/api.yaml", resource.path());
        assertTrue(resource.before(), resource.before().endsWith("spec:\n  replicas: 1\n"));
        assertTrue(resource.after(), resource.after().endsWith("spec:\n  replicas: 3\n"));
        assertTrue(resource.after().contains("# Source: service/templates/deployment.yaml\n"));
    }
    public void testRealHelmfileOutputIsGroupedByReleaseWithoutFalsePartialWarnings() throws Exception {
        try (var input = getClass().getResourceAsStream("/helmfile-diff/standard-output.txt")) {
            assertNotNull(input);
            var result = HelmfileDiffOutput.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertSize(2, result.resources());
            assertFalse(result.partial());
            assertEquals("api/staging/Deployment/api.yaml", result.resources().getFirst().path());
            assertEquals("worker/staging/Deployment/worker.yaml", result.resources().getLast().path());
            assertTrue(result.resources().getLast().before().contains("replicas: 1"));
            assertTrue(result.resources().getLast().after().contains("replicas: 5"));
            assertTrue(result.resources().getLast().after().contains("image: nginx:stable"));
        }
    }
    public void testAddedDeletedAndClusterScopedResourcesWithAnsiAndWindowsLineEndings() {
        var result = HelmfileDiffOutput.parse(("\u001b[32m, permissions, ClusterRole (rbac.authorization.k8s.io/v1) has been added:\u001b[0m\n"
                + "+ apiVersion: rbac.authorization.k8s.io/v1\n+ kind: ClusterRole\n\n"
                + "staging, old, ConfigMap (v1) has been removed:\n- kind: ConfigMap\n- metadata:\n-   name: old\n").replace("\n", "\r\n"));
        assertSize(2, result.resources());
        assertFalse(result.partial());
        assertEquals("_cluster/ClusterRole/permissions.yaml", result.resources().getFirst().path());
        assertEquals("", result.resources().getFirst().before());
        assertEquals("", result.resources().getLast().after());
    }
    public void testYamlPrefixesAndBlankLinesArePreservedExactly() {
        var result = HelmfileDiffOutput.parse("staging, cfg, ConfigMap (v1) has changed:\n  data:\n    value: |\n\n-     - old\n+     + new\n");
        assertEquals("data:\n  value: |\n\n    - old\n", result.resources().getFirst().before());
        assertEquals("data:\n  value: |\n\n    + new\n", result.resources().getFirst().after());
    }
    public void testOmittedContextAndUnknownOutputAreNotReportedAsComplete() {
        var result = HelmfileDiffOutput.parse("hook output\n" + MODIFIED + "...\nunknown renderer output\n");
        assertTrue(result.partial());
        assertTrue(result.resources().getFirst().partial());
        assertEquals(List.of("hook output", "unknown renderer output"), result.unparsed());
        assertTrue(result.resources().getFirst().before().contains("# ... context omitted"));
        assertFalse(HelmfileDiffOutput.parse("[]\n").unparsed().isEmpty());
    }
    public void testSuppressedResourcesAndOwnershipAreRecognized() {
        var result = HelmfileDiffOutput.parse("staging, secret, Secret (v1) has changed:\n+ Changes suppressed on sensitive content of type Secret\n"
                + "staging, cfg, ConfigMap (v1) has changed, but diff is empty after suppression.\n"
                + "staging, service, Service (v1) changed ownership:\n- old-owner\n+ new-owner\n");
        assertSize(3, result.resources());
        assertTrue(result.resources().get(0).suppressed());
        assertTrue(result.resources().get(1).suppressed());
        assertEquals(HelmfileDiffOutput.Status.OWNERSHIP, result.resources().get(2).status());
    }
    public void testNativeFileChooserIncludesAggregateStatusesYamlAndSeparateDuplicateResources() throws Exception {
        var result = HelmfileDiffOutput.parse(MODIFIED + MODIFIED + "staging, added, Service (v1) has been added:\n+ kind: Service\n");
        var chain = HelmfileDiffViewer.chain(getProject(), command("", "staging"), result);
        assertEquals(Boolean.TRUE, chain.getUserData(DiffUserDataKeys.FORCE_READ_ONLY));
        var files = chain.getListSelection().getList();
        assertSize(4, files);
        assertEquals("All changes.yaml", files.getFirst().getName());
        assertEquals("staging/Deployment/api (2).yaml", files.get(2).getName());
        assertEquals(FileStatus.ADDED, files.getLast().getFileStatus());
        var all = request(files.getFirst());
        assertTrue(side(all, 0).contains("replicas: 1"));
        assertTrue(side(all, 1).contains("replicas: 3"));
        var resource = request(files.get(1));
        assertEquals(List.of("Deployed", "Desired"), resource.getContentTitles());
        assertEquals(YAMLFileType.YML, resource.getContents().getFirst().getContentType());
        assertEquals(Boolean.TRUE, resource.getUserData(DiffUserDataKeys.FORCE_READ_ONLY));
        var additions = request(files.getLast());
        assertEquals("", side(additions, 0));
    }
    public void testReleaseAndWholeStateScopesHaveExactSelectorsAndControlledDiffOptions() {
        var full = command("", "staging");
        assertNull(full.problem());
        assertTrue(full.wholeState());
        var args = full.commandLine("helmfile", Map.of("HELM_DIFF_TOOL", "external-tool"), true);
        assertFalse(args.getParametersList().getList().contains("--selector"));
        assertEquals("", args.getEnvironment().get("HELM_DIFF_TOOL"));
        assertEquals("false", args.getEnvironment().get("HELM_DIFF_COLOR"));
        assertTrue(args.getParametersList().getList().containsAll(List.of("--context", "-1", "--output", "diff", "--detailed-exitcode")));
        assertEquals("name=api", command("api", "staging").selector());
        assertNotNull(new HelmfileCommand(state.toString(), "", "ns", "staging", HelmfileOperation.DIFF).problem());
        for (var operation : HelmfileOperation.values()) if (operation != HelmfileOperation.DIFF)
            assertNotNull(new HelmfileCommand(state.toString(), "", "", "staging", operation).problem());
    }
    public void testExitTwoOpensNativeDiffAndKeepsRunSuccessfulWhileIgnoringStderr() throws Exception {
        binary(MODIFIED, "error log\nstaging, fake, Secret (v1) has been added:\n+ secret\n", 2);
        var handler = run(command("api", "staging"));
        PlatformTestUtil.waitWithEventsDispatching("Native diff viewer did not open", () -> !shown.isEmpty(), 10);
        assertEquals(Integer.valueOf(0), handler.getExitCode());
        assertEquals(Integer.valueOf(2), handler.getUserData(HelmfileDiffRun.EXIT_CODE));
        assertSize(2, shown.getFirst().getRequests());
        assertTrue(shown.getFirst().getRequests().get(1).getName().contains("Deployment/api.yaml"));
    }
    public void testCommandFailureDoesNotDisplayPartialDiffAsSuccessful() throws Exception {
        binary(MODIFIED, "Kubernetes context not found", 1);
        var handler = run(command("api", "staging"));
        PlatformTestUtil.waitWithEventsDispatching("Failure notification missing", () -> !notifications.isEmpty(), 10);
        assertEquals(Integer.valueOf(1), handler.getExitCode());
        assertTrue(shown.isEmpty());
        assertEquals(NotificationType.ERROR, notifications.getLast().getType());
    }
    public void testStoppingAProcessDoesNotOpenItsPartialDiff() throws Exception {
        binary(MODIFIED, "", 0);
        Path ready = directory.resolve("ready");
        Files.writeString(executable, Files.readString(executable).replace("exit 0", "touch '" + ready + "'\nread ignored"));
        var handler = HelmfileExecution.start(getProject(), command("api", "staging"), Map.of(), true);
        handler.startNotify();
        try {
            PlatformTestUtil.waitWithEventsDispatching("Diff process did not start", () -> Files.exists(ready), 10);
            handler.destroyProcess();
            PlatformTestUtil.waitWithEventsDispatching("Stopped diff did not terminate", handler::isProcessTerminated, 10);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertTrue(shown.isEmpty());
            assertTrue(notifications.isEmpty());
        } finally { if (!handler.isProcessTerminated()) ((KillableColoredProcessHandler) handler).killProcess(); }
    }
    public void testNoChangesAndChangesWithoutDiffOutputAreDistinct() throws Exception {
        binary("", "", 0);
        run(command("", "staging"));
        PlatformTestUtil.waitWithEventsDispatching("No-change notification missing", () -> !notifications.isEmpty(), 10);
        assertEquals("No Helmfile changes", notifications.getLast().getTitle());
        notifications.clear();
        binary("", "", 2);
        run(command("", "staging"));
        PlatformTestUtil.waitWithEventsDispatching("Missing manifest notification missing", () -> !notifications.isEmpty(), 10);
        assertEquals(NotificationType.WARNING, notifications.getLast().getType());
        assertTrue(shown.isEmpty());
    }
    public void testProtectedFullStateDiffConfirmsScopeAndCanBeCancelled() {
        TestDialogManager.setTestDialog(message -> {
            assertTrue(message, message.contains("All releases"));
            assertTrue(message, message.contains("production"));
            return Messages.NO;
        }, getTestRootDisposable());
        assertThrows(com.intellij.openapi.progress.ProcessCanceledException.class, () ->
                HelmfileExecution.start(getProject(), command("", "production"), Map.of(), true));
        assertFalse(Files.exists(executable));
    }
    public void testFullStateDialogHasEnvironmentsAndReleaseDialogStillRequiresRenderedName() {
        myFixture.addFileToProject("base.yaml", "environments:\n  staging: {}\n");
        var file = myFixture.configureByText("helmfile.yaml.gotmpl", "bases: [base.yaml]\n---\nreleases:\n  - name: {{ .Values.name }}\n");
        var dialog = new HelmfileReleaseDialog(file);
        try {
            assertTrue(dialog.command().wholeState());
            assertTrue(dialog.getTitle().contains("Diff Helmfile"));
        } finally { Disposer.dispose(dialog.getDisposable()); }
        var name = HelmfileReleaseTarget.at(file, file.getText().indexOf(".Values"));
        var release = new HelmfileReleaseDialog(name, HelmfileOperation.DIFF);
        try { assertNotNull(release.doValidate()); }
        finally { Disposer.dispose(release.getDisposable()); }
        assertTrue(myFixture.findAllGutters().stream().anyMatch(gutter -> "Diff all Helmfile releases".equals(gutter.getTooltipText())));
        List<com.intellij.openapi.actionSystem.AnAction> actions = new ArrayList<>();
        com.intellij.psi.util.PsiTreeUtil.processElements(HelmfileValues.yaml(file), element -> {
            var marker = new HelmfileReleaseLineMarkerContributor().getInfo(element);
            if (marker != null) actions.addAll(Arrays.asList(marker.actions));
            return true;
        });
        assertTrue(actions.stream().anyMatch(action -> action instanceof HelmfileDiffAction
                && "Diff All Releases…".equals(action.getTemplatePresentation().getText())));
    }
}
