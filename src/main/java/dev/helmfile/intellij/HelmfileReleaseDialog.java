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
import com.intellij.psi.PsiFile;

final class HelmfileReleaseDialog extends DialogWrapper {
    private final Project project;
    private final String file;
    private final HelmfileOperation operation;
    private final boolean wholeState;
    private final ComboBox<String> environments;
    private final JBTextField release;
    private final JBTextField namespace;
    private final JBTextArea preview = new JBTextArea(3, 65);
    private final JBLabel protection = new JBLabel();

    HelmfileReleaseDialog(YAMLKeyValue declaration, HelmfileOperation operation) {
        this(declaration.getContainingFile(), declaration, operation);
    }
    HelmfileReleaseDialog(PsiFile file) {
        this(file, null, HelmfileOperation.DIFF);
    }
    HelmfileReleaseDialog(PsiFile file, HelmfileOperation operation) {
        this(file, null, operation);
    }
    private HelmfileReleaseDialog(PsiFile state, YAMLKeyValue declaration, HelmfileOperation operation) {
        super(state.getProject());
        project = state.getProject();
        file = state.getOriginalFile().getVirtualFile().getPath();
        this.operation = operation;
        wholeState = declaration == null;
        Set<String> names = new TreeSet<>(HelmfileValues.model(state).environments());
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
        setTitle(operation.label + (wholeState ? " Helmfile" : " Helmfile Release"));
        setOKButtonText(operation == HelmfileOperation.APPLY ? "Review changes" : "Run " + operation.label);
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
        preview.setText((operation == HelmfileOperation.APPLY ? command.withOperation(HelmfileOperation.DIFF) : command)
                .commandLine(HelmfileSettings.get(project).executable(), Map.of(), true).getCommandLineString());
    }
    @Override protected ValidationInfo doValidate() {
        if (!wholeState && release.getText().isBlank()) return new ValidationInfo("Enter the rendered release name.", release);
        String problem = command().problem();
        if (problem == null) problem = HelmfileExecution.protectionProblem(HelmfileSettings.get(project).protectedEnvironments());
        return problem == null ? null : new ValidationInfo(problem);
    }
    @Override public JComponent getPreferredFocusedComponent() { return !wholeState && release.getText().isEmpty() ? release : environments; }
    @Override protected String getDimensionServiceKey() { return "Helmfile.ReleaseAction"; }
    @Override protected JComponent createCenterPanel() {
        var form = FormBuilder.createFormBuilder().addLabeledComponent("Environment:", environments);
        if (wholeState) form.addLabeledComponent("Scope:", new JBLabel("All releases in this Helmfile"));
        else form.addLabeledComponent("Release name:", release)
                .addTooltip("For a templated release name, enter its rendered name for this environment.")
                .addLabeledComponent("Namespace selector (optional):", namespace)
                .addTooltip("Use a namespace to distinguish releases with the same name.");
        return form.addComponent(protection).addLabeledComponent("Command:", preview, true).getPanel();
    }
}
