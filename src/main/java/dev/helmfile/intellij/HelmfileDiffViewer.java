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

/** Native IDE diff window, including its standard file chooser and next/previous file actions. */
final class HelmfileDiffViewer {
    static ChangeDiffRequestChain chain(Project project, HelmfileCommand command, HelmfileDiffOutput.Result result) {
        List<ChangeDiffRequestChain.Producer> files = new ArrayList<>();
        String title = "Helmfile diff: " + command.scope() + " [" + command.environment() + "]";
        StringBuilder before = new StringBuilder(), after = new StringBuilder();
        for (var resource : result.resources()) {
            append(before, resource.path(), resource.before(), resource.suppressed());
            append(after, resource.path(), resource.after(), resource.suppressed());
        }
        files.add(new Producer(project, title, "All changes.yaml", before.toString(), after.toString(), FileStatus.MODIFIED, result.partial(), false));
        Map<String, Integer> occurrences = new HashMap<>();
        for (var resource : result.resources()) {
            int occurrence = occurrences.merge(resource.path(), 1, Integer::sum);
            String path = resource.path();
            if (occurrence > 1) path = path.substring(0, path.length() - 5) + " (" + occurrence + ").yaml";
            FileStatus status = switch (resource.status()) {
                case ADDED -> FileStatus.ADDED;
                case DELETED -> FileStatus.DELETED;
                default -> FileStatus.MODIFIED;
            };
            files.add(new Producer(project, title, path, resource.before(), resource.after(), status, resource.partial(), resource.suppressed()));
        }
        var chain = new ChangeDiffRequestChain(files, 0);
        chain.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true);
        chain.putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true);
        chain.putUserData(DiffUserDataKeys.PLACE, "HelmfileDiff");
        return chain;
    }
    static void show(Project project, HelmfileCommand command, HelmfileDiffOutput.Result result) {
        DiffManager.getInstance().showDiff(project, chain(project, command, result), DiffDialogHints.DEFAULT);
    }
    private static void append(StringBuilder target, String path, String text, boolean suppressed) {
        if (text.isEmpty() && !suppressed) return;
        if (!target.isEmpty()) target.append("---\n");
        target.append("# ").append(path).append('\n');
        target.append(suppressed ? "# Changes suppressed by Helmfile configuration.\n" : text);
    }
    private record Producer(Project project, String title, String path, String before, String after, FileStatus status,
                            boolean partial, boolean suppressed) implements ChangeDiffRequestChain.Producer {
        @Override public @NotNull String getName() { return path; }
        @Override public @NotNull FilePath getFilePath() { return VcsUtil.getFilePath(path, false); }
        @Override public @NotNull FileStatus getFileStatus() { return status; }
        @Override public @NotNull DiffRequest process(@NotNull UserDataHolder context, @NotNull ProgressIndicator indicator) {
            indicator.checkCanceled();
            if (suppressed) return new MessageDiffRequest(title + " — " + path, "Changes to this resource are suppressed by the Helmfile configuration.");
            var factory = DiffContentFactory.getInstance();
            var request = new SimpleDiffRequest(title + " — " + path,
                    factory.create(project, before, YAMLFileType.YML), factory.create(project, after, YAMLFileType.YML),
                    "Deployed" + (partial ? " (partial output)" : ""), "Desired" + (partial ? " (partial output)" : ""));
            request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true);
            request.putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true);
            return request;
        }
    }
    private HelmfileDiffViewer() {}
}
