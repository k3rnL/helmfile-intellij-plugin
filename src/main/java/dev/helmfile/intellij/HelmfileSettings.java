package dev.helmfile.intellij;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SimpleModificationTracker;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
@State(name = "HelmfileSettings", storages = @Storage("helmfile.xml"))
public final class HelmfileSettings extends SimpleModificationTracker implements PersistentStateComponent<HelmfileSettings.State> {
    public static final class State { public String environment = "default"; }
    private State state = new State();
    static HelmfileSettings get(Project project) { return project.getService(HelmfileSettings.class); }
    public String environment() { return state.environment; }
    public void select(String environment) { state.environment = environment; incModificationCount(); }
    @Override public @NotNull State getState() { return state; }
    @Override public void loadState(@NotNull State state) { this.state = state; if (state.environment == null || state.environment.isBlank()) state.environment = "default"; incModificationCount(); }
}
