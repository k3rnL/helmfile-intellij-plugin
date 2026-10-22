package dev.helmfile.intellij;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import java.util.regex.*;
import javax.swing.*;

public final class HelmfileConfigurable implements Configurable {
    private final Project project;
    private TextFieldWithBrowseButton executable;
    private JBTextField protectedEnvironments;
    public HelmfileConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "Helmfile"; }
    @Override public JComponent createComponent() {
        executable = new TextFieldWithBrowseButton();
        executable.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Helmfile Executable"));
        protectedEnvironments = new JBTextField();
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Helmfile executable:", executable)
                .addTooltip("Use 'helmfile' from PATH or an absolute executable path.")
                .addLabeledComponent("Protected environments (regex):", protectedEnvironments)
                .addTooltip("Matches the entire environment name. Default: prod.*. Leave empty to disable protection.")
                .addTooltip("Every matching action requires confirmation, including template and reruns.")
                .addComponentFillVertically(new JPanel(), 0).getPanel();
    }
    @Override public boolean isModified() {
        HelmfileSettings settings = HelmfileSettings.get(project);
        return !settings.executable().equals(executable.getText().trim()) || !settings.protectedEnvironments().equals(protectedEnvironments.getText());
    }
    @Override public void apply() throws ConfigurationException {
        if (executable.getText().isBlank()) throw new ConfigurationException("Enter a Helmfile executable.");
        try { Pattern.compile(protectedEnvironments.getText()); }
        catch (PatternSyntaxException error) { throw new ConfigurationException("Invalid environment regex: " + error.getDescription()); }
        HelmfileSettings.get(project).configure(executable.getText().trim(), protectedEnvironments.getText());
    }
    @Override public void reset() {
        HelmfileSettings settings = HelmfileSettings.get(project);
        executable.setText(settings.executable());
        protectedEnvironments.setText(settings.protectedEnvironments());
    }
    @Override public void disposeUIResources() { executable = null; protectedEnvironments = null; }
}
