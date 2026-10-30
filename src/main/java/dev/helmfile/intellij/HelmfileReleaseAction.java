package dev.helmfile.intellij;

import com.intellij.execution.*;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;

public class HelmfileReleaseAction extends AnAction implements com.intellij.openapi.project.DumbAware {
    private final HelmfileOperation operation;
    private final SmartPsiElementPointer<YAMLKeyValue> pointer;
    HelmfileReleaseAction(HelmfileOperation operation, YAMLKeyValue declaration) {
        super(operation.label + " Release…", operation.description, operation.icon);
        this.operation = operation;
        pointer = declaration == null ? null : SmartPointerManager.createPointer(declaration);
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public boolean equals(Object other) {
        return other instanceof HelmfileReleaseAction action && operation == action.operation && java.util.Objects.equals(pointer, action.pointer);
    }
    @Override public int hashCode() { return java.util.Objects.hash(operation, pointer); }
    private YAMLKeyValue declaration(AnActionEvent event) {
        if (pointer != null) return pointer.getElement();
        var editor = event.getData(CommonDataKeys.EDITOR);
        return editor == null ? null : HelmfileReleaseTarget.at(event.getData(CommonDataKeys.PSI_FILE), editor.getCaretModel().getOffset());
    }
    @Override public void update(@NotNull AnActionEvent event) {
        YAMLKeyValue name = declaration(event);
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && name != null && !HelmfileReleaseTarget.file(name).isEmpty());
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        YAMLKeyValue name = declaration(event);
        Project project = event.getProject();
        if (name == null || project == null) return;
        HelmfileReleaseDialog dialog = new HelmfileReleaseDialog(name, operation);
        if (!dialog.showAndGet()) return;
        HelmfileCommand command = dialog.command();
        run(project, command);
    }
    static void run(Project project, HelmfileCommand command) {
        run(project, command, java.util.Map.of(), true, false);
    }
    static void runReviewed(Project project, HelmfileCommand command, java.util.Map<String, String> variables, boolean passParent) {
        run(project, command, variables, passParent, true);
    }
    private static void run(Project project, HelmfileCommand command, java.util.Map<String, String> variables, boolean passParent, boolean reviewed) {
        // Use the standard run manager, so the configuration can be edited, saved, and rerun.
        RunManager manager = RunManager.getInstance(project);
        var type = ConfigurationTypeUtil.findConfigurationType(HelmfileRunConfigurationType.class);
        var settings = manager.createConfiguration("Helmfile " + command.operation().command() + " " + (command.wholeState() ? "all releases" : command.release()) + " [" + command.environment() + "]", type.getConfigurationFactories()[0]);
        HelmfileRunConfiguration configuration = (HelmfileRunConfiguration) settings.getConfiguration();
        configuration.stateFile = command.file();
        configuration.release = command.release();
        configuration.namespace = command.namespace();
        configuration.environment = command.environment();
        configuration.operation = command.operation();
        configuration.variables = new java.util.LinkedHashMap<>(variables);
        configuration.passParentEnvironment = passParent;
        configuration.reviewedApply = reviewed;
        FileDocumentManager.getInstance().saveAllDocuments();
        manager.setTemporaryConfiguration(settings);
        manager.setSelectedConfiguration(settings);
        try { ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).buildAndExecute(); }
        catch (ExecutionException error) { Messages.showErrorDialog(project, error.getMessage(), "Cannot Run Helmfile"); }
    }
    public static final class Template extends HelmfileReleaseAction { public Template() { super(HelmfileOperation.TEMPLATE, null); } }
    public static final class Diff extends HelmfileReleaseAction { public Diff() { super(HelmfileOperation.DIFF, null); } }
    public static final class Apply extends HelmfileReleaseAction { public Apply() { super(HelmfileOperation.APPLY, null); } }
    public static final class Destroy extends HelmfileReleaseAction { public Destroy() { super(HelmfileOperation.DESTROY, null); } }
}
