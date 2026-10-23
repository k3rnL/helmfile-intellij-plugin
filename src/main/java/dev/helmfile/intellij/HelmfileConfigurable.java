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
    private TextFieldWithBrowseButton helmExecutable;
    private JBTextField protectedEnvironments;
    private JCheckBox repositoryDownloads;
    public HelmfileConfigurable(Project project) { this.project = project; }
    @Override public String getDisplayName() { return "Helmfile"; }
    @Override public JComponent createComponent() {
        executable = new TextFieldWithBrowseButton();
        executable.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Helmfile Executable"));
        helmExecutable = new TextFieldWithBrowseButton();
        helmExecutable.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Helm Executable"));
        protectedEnvironments = new JBTextField();
        repositoryDownloads = new JCheckBox("Download chart repository indexes in the background");
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Helmfile executable:", executable)
                .addTooltip("Use 'helmfile' from PATH or an absolute executable path.")
                .addLabeledComponent("Helm executable:", helmExecutable)
                .addTooltip("Used to download chart sources for Ctrl+Click navigation. Defaults to 'helm' from PATH.")
                .addLabeledComponent("Protected environments (regex):", protectedEnvironments)
                .addTooltip("Matches the entire environment name. Default: prod.*. Leave empty to disable protection.")
                .addTooltip("Every matching action requires confirmation, including template and reruns.")
                .addComponent(repositoryDownloads)
                .addTooltip("Enables chart and version completion and validation. Uses repository credentials and the IDE's proxy settings.")
                .addComponentFillVertically(new JPanel(), 0).getPanel();
    }
    @Override public boolean isModified() {
        HelmfileSettings settings = HelmfileSettings.get(project);
        return !settings.executable().equals(executable.getText().trim()) || !settings.protectedEnvironments().equals(protectedEnvironments.getText())
                || !settings.helmExecutable().equals(helmExecutable.getText().trim())
                || settings.repositoryDownloads() != repositoryDownloads.isSelected();
    }
    @Override public void apply() throws ConfigurationException {
        if (executable.getText().isBlank()) throw new ConfigurationException("Enter a Helmfile executable.");
        if (helmExecutable.getText().isBlank()) throw new ConfigurationException("Enter a Helm executable.");
        try { Pattern.compile(protectedEnvironments.getText()); }
        catch (PatternSyntaxException error) { throw new ConfigurationException("Invalid environment regex: " + error.getDescription()); }
        HelmfileSettings.get(project).configure(executable.getText().trim(), protectedEnvironments.getText());
        HelmfileSettings.get(project).helmExecutable(helmExecutable.getText().trim());
        HelmfileSettings.get(project).repositoryDownloads(repositoryDownloads.isSelected());
    }
    @Override public void reset() {
        HelmfileSettings settings = HelmfileSettings.get(project);
        executable.setText(settings.executable());
        helmExecutable.setText(settings.helmExecutable());
        protectedEnvironments.setText(settings.protectedEnvironments());
        repositoryDownloads.setSelected(settings.repositoryDownloads());
    }
    @Override public void disposeUIResources() { executable = null; helmExecutable = null; protectedEnvironments = null; repositoryDownloads = null; }
}
