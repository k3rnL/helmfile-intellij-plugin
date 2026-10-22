package dev.helmfile.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.*;
import com.intellij.util.ui.FormBuilder;
import java.util.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLKeyValue;

final class HelmfileReleaseDialog extends DialogWrapper {
    private final Project project;
    private final String file;
    private final HelmfileOperation operation;
    private final ComboBox<String> environments;
    private final JBTextField release;
    private final JBTextField namespace;
    private final JBTextArea preview = new JBTextArea(3, 65);
    private final JBLabel protection = new JBLabel();

    HelmfileReleaseDialog(YAMLKeyValue declaration, HelmfileOperation operation) {
        super(declaration.getProject());
        project = declaration.getProject();
        file = HelmfileReleaseTarget.file(declaration);
        this.operation = operation;
        Set<String> names = new TreeSet<>(HelmfileValues.model(declaration.getContainingFile()).environments());
        String selected = HelmfileSettings.get(project).environment();
        names.add(selected);
        environments = new ComboBox<>(names.toArray(String[]::new));
        environments.setEditable(true);
        environments.setSelectedItem(selected);
        release = new JBTextField(HelmfileReleaseTarget.literal(declaration));
        namespace = new JBTextField(HelmfileReleaseTarget.namespace(declaration));
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        preview.setBackground(com.intellij.util.ui.UIUtil.getPanelBackground());
        DocumentAdapter changed = new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent event) { updatePreview(); }
        };
        release.getDocument().addDocumentListener(changed);
        namespace.getDocument().addDocumentListener(changed);
        ((JTextField) environments.getEditor().getEditorComponent()).getDocument().addDocumentListener(changed);
        setTitle(operation.label + " Helmfile Release");
        setOKButtonText("Run " + operation.label);
        init();
        updatePreview();
    }
    HelmfileCommand command() {
        return new HelmfileCommand(file, release.getText().trim(), namespace.getText().trim(),
                Objects.toString(environments.getEditor().getItem(), "").trim(), operation);
    }
    private void updatePreview() {
        HelmfileCommand command = command();
        String regex = HelmfileSettings.get(project).protectedEnvironments();
        String problem = HelmfileExecution.protectionProblem(regex);
        protection.setText(problem != null ? problem : HelmfileExecution.protectedEnvironment(regex, command.environment())
                ? "Protected environment — confirmation is required before execution." : " ");
        preview.setText(command.commandLine(HelmfileSettings.get(project).executable(), Map.of(), true).getCommandLineString());
    }
    @Override protected ValidationInfo doValidate() {
        String problem = command().problem();
        if (problem == null) problem = HelmfileExecution.protectionProblem(HelmfileSettings.get(project).protectedEnvironments());
        return problem == null ? null : new ValidationInfo(problem);
    }
    @Override public JComponent getPreferredFocusedComponent() { return release.getText().isEmpty() ? release : environments; }
    @Override protected String getDimensionServiceKey() { return "Helmfile.ReleaseAction"; }
    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder().addLabeledComponent("Environment:", environments)
                .addLabeledComponent("Release name:", release)
                .addTooltip("For a templated release name, enter its rendered name for this environment.")
                .addLabeledComponent("Namespace selector (optional):", namespace)
                .addTooltip("Use a namespace to distinguish releases with the same name.")
                .addComponent(protection).addLabeledComponent("Command:", preview, true).getPanel();
    }
}
