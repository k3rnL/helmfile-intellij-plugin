package dev.helmfile.intellij;

import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.*;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import java.util.*;
import javax.swing.*;
import org.jetbrains.annotations.NotNull;

final class HelmfileRunConfigurationEditor extends SettingsEditor<HelmfileRunConfiguration> {
    private final TextFieldWithBrowseButton file = new TextFieldWithBrowseButton();
    private final JBTextField release = new JBTextField();
    private final JBTextField namespace = new JBTextField();
    private final ComboBox<String> environment = new ComboBox<>();
    private final ComboBox<HelmfileOperation> operation = new ComboBox<>(HelmfileOperation.values());
    private final EnvironmentVariablesComponent variables;
    private final Project project;
    HelmfileRunConfigurationEditor(Project project) {
        this.project = project;
        variables = environmentVariablesEditor(project);
        file.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Helmfile State File"));
        environment.setEditable(true);
        variables.setText("Environment variables:");
        file.addActionListener(event -> refreshEnvironments(environment(), true));
    }
    private static EnvironmentVariablesComponent environmentVariablesEditor(Project project) {
        // The public Project constructor was added after 2026.1; the no-arg one is deprecated in 2026.3.
        // Choose by availability so older IDEs keep working without calling deprecated APIs on newer IDEs.
        try {
            try { return EnvironmentVariablesComponent.class.getConstructor(Project.class).newInstance(project); }
            catch (NoSuchMethodException olderIde) { return EnvironmentVariablesComponent.class.getConstructor().newInstance(); }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot create IntelliJ's environment variables editor", error);
        }
    }
    private String environment() { return Objects.toString(environment.getEditor().getItem(), "").trim(); }
    private void refreshEnvironments(String selected, boolean discover) {
        Set<String> names = new TreeSet<>(List.of("default", selected));
        if (discover) {
            var virtual = LocalFileSystem.getInstance().findFileByPath(file.getText());
            PsiFile psi = virtual == null ? null : PsiManager.getInstance(project).findFile(virtual);
            if (psi != null) names.addAll(HelmfileValues.model(psi).environments());
        }
        environment.setModel(new DefaultComboBoxModel<>(names.toArray(String[]::new)));
        environment.setSelectedItem(selected);
    }
    @Override protected void resetEditorFrom(@NotNull HelmfileRunConfiguration configuration) {
        file.setText(configuration.stateFile);
        release.setText(configuration.release);
        namespace.setText(configuration.namespace);
        operation.setSelectedItem(configuration.operation);
        refreshEnvironments(configuration.environment, true);
        variables.setEnvs(configuration.variables);
        variables.setPassParentEnvs(configuration.passParentEnvironment);
    }
    @Override protected void applyEditorTo(@NotNull HelmfileRunConfiguration configuration) throws ConfigurationException {
        HelmfileCommand command = new HelmfileCommand(file.getText().trim(), release.getText().trim(), namespace.getText().trim(),
                environment(), (HelmfileOperation) operation.getSelectedItem());
        String problem = command.problem();
        if (problem != null) throw new ConfigurationException(problem);
        configuration.stateFile = command.file();
        configuration.release = command.release();
        configuration.namespace = command.namespace();
        configuration.environment = command.environment();
        configuration.operation = command.operation();
        configuration.variables = new LinkedHashMap<>(variables.getEnvs());
        configuration.passParentEnvironment = variables.isPassParentEnvs();
    }
    @Override protected @NotNull JComponent createEditor() {
        return FormBuilder.createFormBuilder().addLabeledComponent("State file:", file)
                .addLabeledComponent("Release name:", release)
                .addTooltip("Leave empty to run the selected command for all releases in the Helmfile.")
                .addLabeledComponent("Namespace selector (optional):", namespace)
                .addLabeledComponent("Environment:", environment).addLabeledComponent("Command:", operation)
                .addComponent(variables)
                .addTooltip("Environment protection is configured in Settings | Tools | Helmfile and checked on every run.")
                .addComponentFillVertically(new JPanel(), 0).getPanel();
    }
}
