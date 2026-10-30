package dev.helmfile.intellij;

import com.intellij.diff.*;
import com.intellij.diff.requests.*;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain;
import com.intellij.vcsUtil.VcsUtil;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.YAMLFileType;

/** The IDE's single-sided YAML viewer, with its standard file chooser, search and copy actions. */
final class HelmfileTemplateViewer {
    static ChangeDiffRequestChain chain(Project project, HelmfileCommand command, HelmfileTemplateOutput.Result result) {
        String title = "Helmfile template: " + command.scope() + " [" + command.environment() + "]";
        List<ChangeDiffRequestChain.Producer> files = new ArrayList<>();
        files.add(new Producer(project, title, "All manifests.yaml", result.text()));
        for (var resource : result.resources()) files.add(new Producer(project, title, resource.path(), resource.text()));
        var chain = new ChangeDiffRequestChain(files, 0);
        chain.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true);
        chain.putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true);
        chain.putUserData(DiffUserDataKeys.PLACE, "HelmfileTemplate");
        return chain;
    }
    static void show(Project project, HelmfileCommand command, HelmfileTemplateOutput.Result result) {
        DiffManager.getInstance().showDiff(project, chain(project, command, result), DiffDialogHints.DEFAULT);
    }
    private record Producer(Project project, String title, String path, String text) implements ChangeDiffRequestChain.Producer {
        @Override public @NotNull String getName() { return path; }
        @Override public @NotNull FilePath getFilePath() { return VcsUtil.getFilePath(path, false); }
        @Override public @NotNull FileStatus getFileStatus() { return FileStatus.NOT_CHANGED; }
        @Override public @NotNull DiffRequest process(@NotNull UserDataHolder context, @NotNull ProgressIndicator indicator) {
            indicator.checkCanceled();
            var factory = DiffContentFactory.getInstance();
            // IntelliJ's one-pane viewer still requires two content slots, one of them EmptyContent.
            // A request with a single slot is rejected by every built-in text diff tool.
            var content = factory.create(project, text, YAMLFileType.YML);
            var request = new SimpleDiffRequest(title + " — " + path, factory.createEmpty(), content, null, "Rendered manifest");
            request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true);
            request.putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true);
            return request;
        }
    }
    private HelmfileTemplateViewer() {}
}
