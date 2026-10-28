package dev.helmfile.intellij;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.*;
import java.util.regex.Pattern;
import org.jetbrains.yaml.psi.*;

final class HelmfileRepositoryCompletion {
    record Suggestion(String text, String repository, String description) {}
    private static final Pattern FIELD = Pattern.compile("^([ \\t]*(?:-\\s*)?)(chart|version):[ \\t]*(['\"]?)([^'\"\\r\\n]*)$");
    static boolean complete(CompletionParameters parameters, CompletionResultSet result) {
        PsiFile file = parameters.getOriginalFile();
        String text = file.getViewProvider().getContents().toString();
        int offset = parameters.getOffset(), line = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        var field = FIELD.matcher(text.substring(line, offset));
        if (!field.matches() || field.group(4).contains("{{") || field.group(4).contains(" #")) return false;
        int keyOffset = line + field.group(1).length();
        List<String> path = HelmfileSchema.completionPath(file, keyOffset + field.group(2).length());
        if (path == null || !Set.of("releases", "dependencies").contains(HelmfileSchema.context(path))) return false;
        String prefix = field.group(4);
        CompletionResultSet values = result.withPrefixMatcher(prefix);
        if (field.group(2).equals("chart")) {
            var repositories = HelmfileRepositories.model(file).named();
            if (!prefix.contains("/")) {
                for (var repository : repositories.values()) values.addElement(LookupElementBuilder
                        .create(new Suggestion(repository.name() + "/", repository.name(), "Repository from " + repository.source()), repository.name() + "/")
                        .withIcon(AllIcons.Nodes.PpLib).withTypeText(repository.access().oci() ? "OCI repository" : "Helm repository")
                        .withTailText("  " + repository.source(), true).withInsertHandler((context, item) ->
                                AutoPopupController.getInstance(context.getProject()).scheduleAutoPopup(context.getEditor())));
            } else {
                String alias = prefix.substring(0, prefix.indexOf('/'));
                var repository = repositories.get(alias);
                if (repository != null && !repository.access().oci()) {
                    var snapshot = HelmfileRepositoryService.get(file.getProject()).snapshot(new HelmfileRepositoryService.Query(repository.access(), ""), true);
                    advertise(result, snapshot);
                    if (snapshot.index() != null) for (var entry : snapshot.index().charts().entrySet()) {
                        var chart = entry.getValue().getFirst();
                        String name = alias + "/" + entry.getKey();
                        values.addElement(LookupElementBuilder.create(new Suggestion(name, alias, chart.description()), name)
                                .withIcon(AllIcons.Nodes.PpLib).withTypeText(chart.version()).withTailText("  " + chart.description(), true)
                                .withStrikeoutness(chart.deprecated()));
                    }
                } else if (repository != null) result.addLookupAdvertisement("OCI registries do not provide a Helm chart index; enter a chart name to complete its versions.");
            }
        } else {
            YAMLFile yaml = HelmfileValues.yaml(file);
            YAMLKeyValue key = yaml == null ? null : PsiTreeUtil.getParentOfType(yaml.findElementAt(keyOffset), YAMLKeyValue.class);
            if (key != null) {
                String chart = HelmfileRepositories.chart(key.getParentMapping(), new HashSet<>());
                var query = HelmfileRepositories.query(file, chart);
                if (query != null) {
                    var snapshot = HelmfileRepositoryService.get(file.getProject()).snapshot(query, true);
                    advertise(result, snapshot);
                    String chartName = query.access().oci() ? query.chart() : chart.substring(chart.indexOf('/') + 1);
                    if (snapshot.index() != null) {
                        List<HelmfileChartIndex.Chart> versions = snapshot.index().charts().getOrDefault(chartName, List.of());
                        for (int i = 0; i < versions.size(); i++) {
                            var version = versions.get(i);
                            values.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder
                                    .create(new Suggestion(version.version(), query.access().name(), version.description()), version.version())
                                    .withTypeText(chart).withIcon(AllIcons.Nodes.Tag).withStrikeoutness(version.deprecated()), versions.size() - i));
                        }
                    }
                }
            }
        }
        result.stopHere();
        return true;
    }
    private static void advertise(CompletionResultSet result, HelmfileRepositoryService.Snapshot snapshot) {
        if (snapshot.loading()) result.addLookupAdvertisement("Loading repository metadata in the background; invoke completion again when ready.");
        else if (snapshot.error() != null) result.addLookupAdvertisement(HelmfileDownloadDiagnostics.preview(snapshot.error()));
    }
    private HelmfileRepositoryCompletion() {}
}
