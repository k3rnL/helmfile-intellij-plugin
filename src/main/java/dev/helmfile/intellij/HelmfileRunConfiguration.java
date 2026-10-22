package dev.helmfile.intellij;

import com.intellij.execution.*;
import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.execution.configurations.*;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import java.util.*;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

public final class HelmfileRunConfiguration extends LocatableConfigurationBase<Object> {
    String stateFile = "";
    String release = "";
    String namespace = "";
    String environment = "default";
    HelmfileOperation operation = HelmfileOperation.TEMPLATE;
    Map<String, String> variables = new LinkedHashMap<>();
    boolean passParentEnvironment = true;

    HelmfileRunConfiguration(Project project, ConfigurationFactory factory, String name) { super(project, factory, name); }
    HelmfileCommand command() { return new HelmfileCommand(stateFile, release, namespace, environment, operation); }
    @Override public @NotNull SettingsEditor<HelmfileRunConfiguration> getConfigurationEditor() { return new HelmfileRunConfigurationEditor(getProject()); }
    @Override public void checkConfiguration() throws RuntimeConfigurationException {
        String problem = command().problem();
        if (problem == null) problem = HelmfileExecution.protectionProblem(HelmfileSettings.get(getProject()).protectedEnvironments());
        if (problem != null) throw new RuntimeConfigurationError(problem);
    }
    @Override public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment executionEnvironment) {
        // Snapshot command options; protection is read afresh for every start, including reruns.
        HelmfileCommand command = command();
        Map<String, String> env = Map.copyOf(variables);
        boolean inherit = passParentEnvironment;
        return new CommandLineState(executionEnvironment) {
            @Override protected @NotNull ProcessHandler startProcess() throws ExecutionException {
                return HelmfileExecution.start(getProject(), command, env, inherit);
            }
        };
    }
    @Override public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);
        Element options = element.getChild("helmfile");
        if (options == null) return;
        stateFile = PathMacroManager.getInstance(getProject()).expandPath(options.getAttributeValue("file", ""));
        release = options.getAttributeValue("release", "");
        namespace = options.getAttributeValue("namespace", "");
        environment = options.getAttributeValue("environment", "default");
        try { operation = HelmfileOperation.valueOf(options.getAttributeValue("operation", "TEMPLATE")); }
        catch (IllegalArgumentException error) { operation = null; }
        passParentEnvironment = Boolean.parseBoolean(options.getAttributeValue("passParentEnvironment", "true"));
        variables = new LinkedHashMap<>();
        EnvironmentVariablesComponent.readExternal(options, variables);
    }
    @Override public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        Element options = new Element("helmfile").setAttribute("file", PathMacroManager.getInstance(getProject()).collapsePath(stateFile))
                .setAttribute("release", release).setAttribute("namespace", namespace).setAttribute("environment", environment)
                .setAttribute("operation", operation == null ? "" : operation.name()).setAttribute("passParentEnvironment", Boolean.toString(passParentEnvironment));
        EnvironmentVariablesComponent.writeExternal(options, variables);
        element.addContent(options);
    }
    @Override public HelmfileRunConfiguration clone() {
        HelmfileRunConfiguration copy = (HelmfileRunConfiguration) super.clone();
        copy.variables = new LinkedHashMap<>(variables);
        return copy;
    }
}
