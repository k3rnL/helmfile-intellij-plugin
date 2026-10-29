package dev.helmfile.intellij;

import com.intellij.execution.*;
import com.intellij.execution.configurations.*;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.*;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.options.*;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTextField;
import org.jdom.Element;
import org.jetbrains.yaml.YAMLLanguage;
import org.jetbrains.yaml.psi.YAMLKeyValue;

public class HelmfileExecutionTest extends BasePlatformTestCase {
    private Path directory;
    private Path state;
    private Path executable;
    @Override protected void setUp() throws Exception {
        super.setUp();
        directory = Files.createTempDirectory("helmfile execution ");
        state = Files.writeString(directory.resolve("helmfile.yaml.gotmpl"), "releases:\n  - name: api\n    chart: example/api\n");
        executable = Files.writeString(directory.resolve("fake helmfile"), """
                #!/bin/sh
                printf 'cwd=%s\\n' "$PWD"
                printf 'kubeconfig=%s\\n' "$KUBECONFIG"
                printf 'arg=%s\\n' "$@"
                exit "${TEST_EXIT_CODE:-0}"
                """);
        assertTrue(executable.toFile().setExecutable(true));
        HelmfileSettings.get(getProject()).configure(executable.toString(), "prod.*");
    }
    @Override protected void tearDown() throws Exception {
        try {
            HelmfileSettings.get(getProject()).loadState(new HelmfileSettings.State());
            if (directory != null) {
                try (var files = Files.walk(directory)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        } finally { super.tearDown(); }
    }
    private HelmfileCommand command(String environment, HelmfileOperation operation) {
        return new HelmfileCommand(state.toString(), "api", "", environment, operation);
    }
    private HelmfileRunConfiguration configuration() {
        var type = ConfigurationTypeUtil.findConfigurationType(HelmfileRunConfigurationType.class);
        HelmfileRunConfiguration configuration = (HelmfileRunConfiguration) type.getConfigurationFactories()[0].createTemplateConfiguration(getProject());
        configuration.stateFile = state.toString();
        configuration.release = "api";
        return configuration;
    }
    private ProcessOutput finish(ProcessHandler handler) {
        CapturingProcessAdapter output = new CapturingProcessAdapter();
        handler.addProcessListener(output);
        handler.startNotify();
        assertTrue("Fake process timed out", handler.waitFor(10_000));
        assertNotNull(handler.getExitCode());
        return output.getOutput();
    }
    public void testEveryOperationUsesExactFileEnvironmentAndReleaseWithoutShell() {
        for (HelmfileOperation operation : HelmfileOperation.values()) {
            HelmfileCommand command = command("quality", operation);
            assertNull(command.problem());
            GeneralCommandLine line = command.commandLine(executable.toString(), Map.of(), true);
            var expected = new ArrayList<>(List.of("--file", state.toString(), "--environment", "quality", "--selector", "name=api", "--interactive=false"));
            if (operation == HelmfileOperation.DIFF) expected.addAll(List.of("--no-color", "diff", "--context", "-1", "--output", "diff", "--detailed-exitcode", "--concurrency", "1"));
            else if (operation == HelmfileOperation.TEMPLATE) expected.addAll(List.of("--no-color", "template"));
            else expected.add(operation.command());
            assertEquals(expected, line.getParametersList().getList());
            assertEquals(directory.toFile(), line.getWorkDirectory());
            assertEquals(GeneralCommandLine.ParentEnvironmentType.CONSOLE, line.getParentEnvironmentType());
        }
        HelmfileCommand scoped = new HelmfileCommand(state.toString(), "api", "quality", "quality", HelmfileOperation.APPLY);
        assertEquals("name=api,namespace=quality", scoped.selector());
    }
    public void testSelectorOperatorsAndTemplatesCannotExpandTheReleaseScope() {
        for (String name : List.of("", "api,namespace=prod", "api --selector name=other", "{{ .Values.name }}", "api\nother", "api!=other")) {
            assertNotNull(new HelmfileCommand(state.toString(), name, "", "quality", HelmfileOperation.DESTROY).problem());
        }
        assertNotNull(new HelmfileCommand(state.toString(), "api", "a,name=other", "quality", HelmfileOperation.APPLY).problem());
        assertNotNull(command(" prod", HelmfileOperation.APPLY).problem());
        assertNotNull(command("prod\n", HelmfileOperation.APPLY).problem());
    }
    public void testDefaultProtectionAndSettingsMigration() {
        HelmfileSettings settings = HelmfileSettings.get(getProject());
        HelmfileSettings.State legacy = new HelmfileSettings.State();
        legacy.executable = null;
        legacy.protectedEnvironments = null;
        settings.loadState(legacy);
        assertEquals("helmfile", settings.executable());
        assertEquals("prod.*", settings.protectedEnvironments());
        assertTrue(HelmfileExecution.protectedEnvironment(settings.protectedEnvironments(), "prod"));
        assertTrue(HelmfileExecution.protectedEnvironment(settings.protectedEnvironments(), "production-eu"));
        assertFalse(HelmfileExecution.protectedEnvironment(settings.protectedEnvironments(), "nonprod"));
        assertFalse(HelmfileExecution.protectedEnvironment("", "production"));
        assertTrue(HelmfileExecution.protectedEnvironment("(?:prod.*|staging)", "staging"));
    }
    public void testCancelProtectedActionsNeverStartsAnyProcess() {
        AtomicInteger confirmations = new AtomicInteger();
        TestDialogManager.setTestDialog(message -> {
            confirmations.incrementAndGet();
            assertTrue(message, message.contains("production"));
            assertTrue(message, message.contains("name=api"));
            assertTrue(message, message.contains(state.toString()));
            return Messages.NO;
        }, getTestRootDisposable());
        // A non-existent binary proves cancellation happens before process creation.
        HelmfileSettings.get(getProject()).configure(directory.resolve("must-not-start").toString(), "prod.*");
        for (HelmfileOperation operation : HelmfileOperation.values()) {
            assertThrows(ProcessCanceledException.class, () -> HelmfileExecution.start(getProject(), command("production", operation), Map.of(), true));
        }
        assertEquals(HelmfileOperation.values().length, confirmations.get());
    }
    public void testConfirmedProtectedActionsStartTheChosenCommandAndAlwaysConfirmAgain() throws Exception {
        AtomicInteger confirmations = new AtomicInteger();
        TestDialogManager.setTestDialog(message -> { confirmations.incrementAndGet(); return Messages.YES; }, getTestRootDisposable());
        for (HelmfileOperation operation : HelmfileOperation.values()) {
            ProcessOutput output = finish(HelmfileExecution.start(getProject(), command("prod-eu", operation), Map.of(), true));
            assertTrue(output.getStdout(), output.getStdout().contains("arg=" + (operation == HelmfileOperation.APPLY ? "diff" : operation.command()) + "\n"));
            assertEquals(0, output.getExitCode());
        }
        finish(HelmfileExecution.start(getProject(), command("prod-eu", HelmfileOperation.TEMPLATE), Map.of(), true));
        assertEquals(HelmfileOperation.values().length + 1, confirmations.get());
    }
    public void testInvalidProtectionPatternBlocksEveryOperationBeforeAnyProcessStarts() {
        HelmfileSettings.get(getProject()).configure(executable.toString(), "[invalid");
        for (HelmfileOperation operation : HelmfileOperation.values()) {
            ExecutionException failure = org.junit.Assert.assertThrows(ExecutionException.class, () -> HelmfileExecution.start(getProject(), command("quality", operation), Map.of(), true));
            assertTrue(failure.getMessage().contains("regex"));
        }
        assertThrows(RuntimeConfigurationError.class, () -> configuration().checkConfiguration());
    }
    public void testNativeRunStatePreservesKubeconfigAndRechecksProtectionOnRerun() throws Exception {
        HelmfileRunConfiguration configuration = configuration();
        configuration.environment = "quality";
        configuration.variables.put("KUBECONFIG", "/first/config:/second/config");
        configuration.variables.put("TEST_EXIT_CODE", "7");
        var executor = DefaultRunExecutor.getRunExecutorInstance();
        ProgramRunner<?> runner = ProgramRunner.getRunner(executor.getId(), configuration);
        assertNotNull("The standard IntelliJ runner must support Helmfile", runner);
        var environment = ExecutionEnvironmentBuilder.create(getProject(), executor, configuration).build();
        CommandLineState runState = (CommandLineState) configuration.getState(executor, environment);
        ExecutionResult result = runState.execute(executor, runner);
        try {
            ProcessOutput output = finish(result.getProcessHandler());
            assertTrue(output.getStdout(), output.getStdout().contains("kubeconfig=/first/config:/second/config\n"));
            assertTrue(output.getStdout(), output.getStdout().contains("cwd=" + directory + "\n"));
            assertEquals(7, result.getProcessHandler().getExitCode().intValue());
        } finally { if (result.getExecutionConsole() != null) Disposer.dispose(result.getExecutionConsole()); }
        // Change the project policy after constructing the run state: reruns must read the new policy.
        HelmfileSettings.get(getProject()).configure(executable.toString(), "quality");
        AtomicInteger confirmations = new AtomicInteger();
        TestDialogManager.setTestDialog(message -> { confirmations.incrementAndGet(); return Messages.NO; }, getTestRootDisposable());
        assertThrows(ProcessCanceledException.class, () -> runState.execute(executor, runner));
        assertEquals(1, confirmations.get());
    }
    public void testRunConfigurationPersistsAndClonesEnvironmentIndependently() throws Exception {
        HelmfileRunConfiguration original = configuration();
        original.namespace = "ns";
        original.environment = "production";
        original.operation = HelmfileOperation.DESTROY;
        original.variables.put("KUBECONFIG", "/a:/b");
        original.passParentEnvironment = false;
        Element xml = new Element("configuration");
        original.writeExternal(xml);
        HelmfileRunConfiguration restored = configuration();
        restored.readExternal(xml);
        assertEquals(original.command(), restored.command());
        assertEquals(original.variables, restored.variables);
        assertFalse(restored.passParentEnvironment);
        HelmfileRunConfiguration copy = original.clone();
        copy.variables.put("KUBECONFIG", "/different");
        assertEquals("/a:/b", original.variables.get("KUBECONFIG"));
        // An unknown serialized operation must fail closed, not fall back to apply or destroy.
        for (String removed : List.of("SOMETHING_ELSE", "DELETE")) {
            xml.getChild("helmfile").setAttribute("operation", removed);
            restored.readExternal(xml);
            assertThrows(RuntimeConfigurationError.class, restored::checkConfiguration);
        }
    }
    public void testReviewedApprovalIsNotPersistedClonedOrReusedOnRerun() throws Exception {
        HelmfileRunConfiguration configuration = configuration();
        configuration.environment = "quality";
        configuration.operation = HelmfileOperation.APPLY;
        configuration.reviewedApply = true;
        assertFalse(configuration.clone().reviewedApply);
        Element xml = new Element("configuration"); configuration.writeExternal(xml);
        var restored = configuration(); restored.readExternal(xml); assertFalse(restored.reviewedApply);
        var executor = DefaultRunExecutor.getRunExecutorInstance();
        ProgramRunner<?> runner = ProgramRunner.getRunner(executor.getId(), configuration);
        var environment = ExecutionEnvironmentBuilder.create(getProject(), executor, configuration).build();
        CommandLineState runState = (CommandLineState) configuration.getState(executor, environment);
        for (String expected : List.of("apply", "diff")) {
            ExecutionResult result = runState.execute(executor, runner);
            try { assertTrue(finish(result.getProcessHandler()).getStdout().contains("arg=" + expected + "\n")); }
            finally { if (result.getExecutionConsole() != null) Disposer.dispose(result.getExecutionConsole()); }
        }
    }
    public void testReleaseGutterAndContextIgnoreTemplatesAndNestedNameFields() {
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", """
                templates:
                  shared:
                    name: shared
                releases:
                  - name: api
                    namespace: quality
                    set:
                      - name: image
                        value: nginx
                  - name: worker
                    inherit:
                      - template: shared
                """);
        var contributor = new HelmfileReleaseLineMarkerContributor();
        assertTrue(RunLineMarkerContributor.EXTENSION.allForLanguage(YAMLLanguage.INSTANCE).stream().anyMatch(HelmfileReleaseLineMarkerContributor.class::isInstance));
        List<RunLineMarkerContributor.Info> markers = new ArrayList<>();
        PsiTreeUtil.processElements(HelmfileValues.yaml(file), element -> {
            var info = contributor.getInfo(element);
            if (info != null) markers.add(info);
            return true;
        });
        assertSize(3, markers);
        var releaseMarker = markers.stream().filter(marker -> marker.actions.length > 1).findFirst().orElseThrow();
        assertEquals(List.of("Template Release…", "Diff Release…", "Apply Release…", "Destroy Release…"),
                Arrays.stream(releaseMarker.actions).map(action -> action.getTemplatePresentation().getText()).toList());
        assertEquals(4, Arrays.stream(releaseMarker.actions).map(action -> action.getTemplatePresentation().getIcon()).distinct().count());
        assertNull(com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("Helmfile.Release.Delete"));
        YAMLKeyValue api = HelmfileReleaseTarget.at(file, file.getText().indexOf("nginx"));
        assertNotNull(api);
        assertEquals("api", HelmfileReleaseTarget.literal(api));
        assertEquals("quality", HelmfileReleaseTarget.namespace(api));
        assertNull(HelmfileReleaseTarget.at(file, file.getText().indexOf("shared\n")));
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        assertTrue("The native run gutter must actually appear", myFixture.findAllGutters().stream().anyMatch(gutter ->
                gutter.getTooltipText() != null && gutter.getTooltipText().contains("Run Helmfile for this release")));
    }
    public void testDialogOffersEnvironmentsFromBasesAndRequiresRenderedReleaseNames() {
        myFixture.addFileToProject("base.yaml", "environments:\n  prod-eu: {}\n  staging: {}\n");
        PsiFile file = myFixture.addFileToProject("helmfile.yaml.gotmpl", """
                bases:
                  - base.yaml
                ---
                releases:
                  - name: '{{ .Values.releaseName }}'
                    chart: example/api
                """);
        YAMLKeyValue name = HelmfileReleaseTarget.at(file, file.getText().indexOf("example/api"));
        assertNotNull(name);
        assertEquals("", HelmfileReleaseTarget.literal(name));
        assertContainsElements(HelmfileValues.model(file).environments(), "prod-eu", "staging");
        HelmfileReleaseDialog dialog = new HelmfileReleaseDialog(name, HelmfileOperation.APPLY);
        try {
            assertNotNull(dialog.command().problem());
            assertEquals("default", dialog.command().environment());
            assertEquals(HelmfileOperation.APPLY, dialog.command().operation());
        } finally { Disposer.dispose(dialog.getDisposable()); }
    }
    public void testRegisteredProjectSettingsRejectInvalidRegexAndSaveChanges() throws Exception {
        var extension = Configurable.PROJECT_CONFIGURABLE.getExtensions(getProject()).stream()
                .filter(entry -> "dev.helmfile.settings".equals(entry.id)).findFirst().orElseThrow();
        assertEquals("tools", extension.parentId);
        Configurable configurable = (Configurable) extension.createConfigurable();
        assertInstanceOf(configurable, HelmfileConfigurable.class);
        try {
            var component = configurable.createComponent();
            configurable.reset();
            assertFalse(configurable.isModified());
            JTextField pattern = com.intellij.util.ui.UIUtil.findComponentsOfType(component, JTextField.class).stream()
                    .filter(field -> field.getText().equals("prod.*")).findFirst().orElseThrow();
            pattern.setText("[");
            assertTrue(configurable.isModified());
            assertThrows(ConfigurationException.class, configurable::apply);
            assertEquals("prod.*", HelmfileSettings.get(getProject()).protectedEnvironments());
            pattern.setText("(?:prod.*|staging)");
            configurable.apply();
            assertEquals("(?:prod.*|staging)", HelmfileSettings.get(getProject()).protectedEnvironments());
            assertFalse(configurable.isModified());
        } finally { configurable.disposeUIResources(); }
    }
    public void testNativeConfigurationEditorRoundTripKeepsCommandAndEnvironmentOverrides() throws Exception {
        HelmfileRunConfiguration configuration = configuration();
        configuration.environment = "prod-eu";
        configuration.operation = HelmfileOperation.DESTROY;
        configuration.variables.put("KUBECONFIG", "/a:/b");
        configuration.passParentEnvironment = false;
        SettingsEditor<HelmfileRunConfiguration> editor = configuration.getConfigurationEditor();
        try {
            assertNotNull(editor.getComponent());
            editor.resetFrom(configuration);
            HelmfileRunConfiguration edited = configuration();
            editor.applyTo(edited);
            assertEquals(configuration.command(), edited.command());
            assertEquals(configuration.variables, edited.variables);
            assertFalse(edited.passParentEnvironment);
        } finally { Disposer.dispose(editor); }
    }
}
