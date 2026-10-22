package dev.helmfile.intellij;

import com.intellij.execution.configurations.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.*;
import org.jetbrains.annotations.NotNull;

public final class HelmfileRunConfigurationType extends ConfigurationTypeBase implements DumbAware {
    public HelmfileRunConfigurationType() {
        super("HelmfileRunConfiguration", "Helmfile", "Run a Helmfile command for a release and environment", AllIcons.Nodes.ConfigFolder);
        addFactory(new ConfigurationFactory(this) {
            @Override public @NotNull String getId() { return "Helmfile"; }
            @Override public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
                return new HelmfileRunConfiguration(project, this, "Helmfile");
            }
        });
    }
}
