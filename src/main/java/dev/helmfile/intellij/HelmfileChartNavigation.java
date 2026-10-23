package dev.helmfile.intellij;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.navigation.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.*;
import com.intellij.pom.PomNamedTarget;
import com.intellij.pom.references.PomService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.*;

final class HelmfileChartNavigation {
    record Request(String chart, String version, String directory, String executable, HelmfileRepositories.Access access,
                   boolean devel, boolean verify, String keyring, String registryConfig) {
        @Override public String toString() { return "Chart " + chart; }
        String problem() {
            if (version.contains("{{")) return "The chart version could not be resolved from static Helmfile values. Only simple value paths and default/quote pipelines are supported.";
            if (access != null && java.util.stream.Stream.of(access.url(), access.username(), access.password(), access.caFile(), access.certFile(), access.keyFile())
                    .anyMatch(value -> value.contains("{{")))
                return "The repository settings could not be resolved for the selected Helmfile environment.";
            if (keyring.contains("{{") || registryConfig.contains("{{")) return "The chart authentication or verification settings could not be resolved.";
            return null;
        }
        boolean needsEnvironment() {
            return java.util.stream.Stream.of(version, keyring, registryConfig,
                            access == null ? "" : access.url(), access == null ? "" : access.username(), access == null ? "" : access.password(),
                            access == null ? "" : access.caFile(), access == null ? "" : access.certFile(), access == null ? "" : access.keyFile())
                    .anyMatch(value -> value.contains("{{") && ENVIRONMENT_VALUE.matcher(value).find());
        }
        GeneralCommandLine commandLine(Path destination) {
            GeneralCommandLine line = new GeneralCommandLine(executable).withCharset(StandardCharsets.UTF_8)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE).withWorkDirectory(directory)
                    .withParameters("pull", "--untar", "--destination", destination.toString(), "--untardir", ".");
            if (!version.isBlank()) line.addParameters("--version", version);
            if (devel) line.addParameter("--devel");
            if (verify) line.addParameter("--verify");
            if (!keyring.isBlank()) line.addParameters("--keyring", keyring);
            if (!registryConfig.isBlank()) line.addParameters("--registry-config", registryConfig);
            String reference = chart;
            if (access != null) {
                String name = chart.substring(chart.indexOf('/') + 1);
                if (chart.startsWith("oci://")) name = chart.substring(chart.lastIndexOf('/') + 1);
                if (access.oci()) reference = "oci://" + access.url().replaceFirst("^(?:oci|https?)://", "").replaceFirst("/+$", "") + "/" + name;
                else { reference = name; line.addParameters("--repo", access.url()); }
                if (!access.username().isBlank()) line.addParameters("--username", access.username());
                if (!access.password().isBlank()) line.addParameters("--password", access.password());
                if (!access.caFile().isBlank()) line.addParameters("--ca-file", access.caFile());
                if (!access.certFile().isBlank()) line.addParameters("--cert-file", access.certFile());
                if (!access.keyFile().isBlank()) line.addParameters("--key-file", access.keyFile());
                if (access.skipTLSVerify()) line.addParameter("--insecure-skip-tls-verify");
                if (access.passCredentials()) line.addParameter("--pass-credentials");
                if (access.plainHttp()) line.addParameter("--plain-http");
            }
            return line.withParameters("--", reference);
        }
    }
    private static final Pattern ENVIRONMENT_VALUE = Pattern.compile("\\.(?:StateValues|Values|Environment\\.Values)\\.");
    private static final Pattern PIPELINE = Pattern.compile("\\{\\{-?\\s*(\\$?\\.(?:Values|StateValues|Environment\\.Values)\\.[A-Za-z0-9_.]+)(?:\\s*\\|\\s*default\\s+(\"[^\"]*\"|`[^`]*`|[A-Za-z0-9._+-]+))?(?:\\s*\\|\\s*quote)?\\s*-?}}");
    static String option(YAMLMapping mapping, String name, Set<YAMLMapping> visiting) {
        if (!visiting.add(mapping) || visiting.size() > 40) return "";
        if (mapping.getKeyValueByKey(name) != null) {
            PsiFile file = mapping.getContainingFile();
            String value = HelmfileRepositories.value(mapping, name);
            value = PIPELINE.matcher(value).replaceAll(match -> {
                String resolved = HelmfileRepositories.resolved(file, "{{ " + match.group(1) + " }}");
                if ((resolved.contains("{{") || resolved.isEmpty() || resolved.equals("0") || resolved.equals("false")) && match.group(2) != null) {
                    resolved = match.group(2);
                    if (resolved.startsWith("\"") || resolved.startsWith("`")) resolved = HelmfileReferences.unquote(resolved);
                }
                return java.util.regex.Matcher.quoteReplacement(resolved);
            });
            return HelmfileRepositories.resolved(file, value);
        }
        YAMLKeyValue inherit = mapping.getKeyValueByKey("inherit");
        if (inherit != null) for (YAMLScalar scalar : PsiTreeUtil.findChildrenOfType(inherit, YAMLScalar.class)) {
            if (!HelmfileTemplates.isInheritance(scalar)) continue;
            YAMLKeyValue inheritance = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue.class);
            YAMLKeyValue except = inheritance.getParentMapping().getKeyValueByKey("except");
            if (except != null && except.getValue() instanceof YAMLSequence sequence && sequence.getItems().stream()
                    .anyMatch(item -> item.getValue() instanceof YAMLScalar excluded && excluded.getTextValue().equals(name))) continue;
            YAMLKeyValue parent = HelmfileTemplates.declarations(mapping.getContainingFile()).get(scalar.getTextValue());
            if (parent != null && parent.getValue() instanceof YAMLMapping template) {
                String value = option(template, name, visiting);
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }
    static Request request(YAMLScalar scalar) {
        String chart = HelmfileValues.pathText(scalar);
        PsiFile file = scalar.getContainingFile();
        var query = HelmfileRepositories.query(file, chart);
        if (query == null && !chart.startsWith("https://") && !chart.startsWith("http://")) return null;
        VirtualFile virtual = file.getOriginalFile().getVirtualFile();
        if (virtual == null || virtual.getParent() == null) return null;
        YAMLKeyValue key = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue.class);
        YAMLMapping mapping = key.getParentMapping();
        var repository = query == null ? null : HelmfileRepositories.model(file).named().get(query.access().name());
        String keyring = option(mapping, "keyring", new HashSet<>());
        String registryConfig = repository == null ? "" : HelmfileRepositories.resolved(file,
                HelmfileRepositories.value(repository.declaration().getParentMapping(), "registryConfig"));
        VirtualFile repositoryFile = repository == null ? virtual : repository.declaration().getContainingFile().getOriginalFile().getVirtualFile();
        String verify = option(mapping, "verify", new HashSet<>());
        if (repository != null) {
            if (keyring.isEmpty()) keyring = relative(repositoryFile, HelmfileRepositories.resolved(file,
                    HelmfileRepositories.value(repository.declaration().getParentMapping(), "keyring")));
            if (verify.isEmpty()) verify = HelmfileRepositories.value(repository.declaration().getParentMapping(), "verify");
        }
        return new Request(chart, option(mapping, "version", new HashSet<>()), virtual.getParent().getPath(),
                HelmfileSettings.get(file.getProject()).helmExecutable(), query == null ? null : query.access(),
                option(mapping, "devel", new HashSet<>()).equals("true"), verify.equals("true"),
                relative(virtual, keyring), relative(repositoryFile, registryConfig));
    }
    private static String relative(VirtualFile source, String value) {
        return value.isBlank() || value.contains("{{") || Path.of(value).isAbsolute() || source == null || source.getParent() == null
                ? value : source.getParent().getPath() + "/" + value;
    }
    static ChartReference reference(YAMLScalar scalar) {
        YAMLKeyValue key = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue.class);
        if (key == null || !HelmfileRepositories.isChartField(key, "chart")) return null;
        String chart = HelmfileValues.pathText(scalar);
        if (chart.isBlank() || chart.contains("{{")) return null;
        int quote = scalar.getText().startsWith("'") || scalar.getText().startsWith("\"") ? 1 : 0;
        String alias = HelmfileRepositories.alias(scalar.getContainingFile(), chart);
        boolean remote = chart.startsWith("oci://") || chart.startsWith("https://") || chart.startsWith("http://")
                || alias != null && HelmfileRepositories.model(scalar.getContainingFile()).named().containsKey(alias);
        if (!remote && localChart(scalar) == null) return null;
        int start = alias == null ? 0 : alias.length() + 1;
        if (chart.startsWith("oci://") || chart.startsWith("http://") || chart.startsWith("https://")) start = chart.lastIndexOf('/') + 1;
        if (start >= chart.length()) return null;
        return new ChartReference(scalar, new TextRange(quote + start, quote + chart.length()));
    }
    private static VirtualFile localChart(YAMLScalar scalar) {
        VirtualFile source = scalar.getContainingFile().getOriginalFile().getVirtualFile();
        String path = HelmfileValues.pathText(scalar);
        if (source == null || source.getParent() == null || path.contains("://")) return null;
        try {
            VirtualFile local = Path.of(path).isAbsolute() ? LocalFileSystem.getInstance().findFileByPath(path) : source.getParent().findFileByRelativePath(path);
            return local != null && local.isDirectory() ? local.findChild("Chart.yaml") : null;
        } catch (java.nio.file.InvalidPathException ignored) { return null; }
    }
    static final class ChartReference extends PsiReferenceBase<YAMLScalar> {
        ChartReference(YAMLScalar scalar, TextRange range) { super(scalar, range, true); }
        @Override public PsiElement resolve() {
            PsiFile file = myElement.getContainingFile();
            VirtualFile local = localChart(myElement);
            if (local != null) return PsiManager.getInstance(file.getProject()).findFile(local);
            Request request = request(myElement);
            return request == null ? null : PomService.convertToPsi(file.getProject(), new Target(myElement, request));
        }
        @Override public Object @NotNull [] getVariants() { return EMPTY_ARRAY; }
    }
    private static final class Target implements PomNamedTarget, NavigationItem {
        private final SmartPsiElementPointer<YAMLScalar> source;
        private final Request request;
        Target(YAMLScalar source, Request request) { this.source = SmartPointerManager.createPointer(source); this.request = request; }
        @Override public void navigate(boolean requestFocus) {
            open(source, true);
        }
        @Override public boolean canNavigate() { return isValid(); }
        @Override public boolean canNavigateToSource() { return isValid(); }
        @Override public boolean isValid() { return source.getElement() != null; }
        @Override public @NotNull String getName() { return "Chart " + request.chart() + (request.version().isEmpty() ? "" : " (" + request.version() + ")"); }
        @Override public ItemPresentation getPresentation() {
            return new ItemPresentation() {
                @Override public String getPresentableText() { return getName(); }
                @Override public Icon getIcon(boolean unused) { return HelmfileFileType.INSTANCE.getIcon(); }
            };
        }
    }
    private static void open(SmartPsiElementPointer<YAMLScalar> source, boolean chooseEnvironment) {
        Request request = currentRequest(source);
        if (request == null) return;
        if (chooseEnvironment && request.needsEnvironment()) {
            PsiFile file = ReadAction.computeBlocking(() -> {
                YAMLScalar scalar = source.getElement();
                return scalar == null ? null : scalar.getContainingFile();
            });
            if (file != null) SelectEnvironmentAction.chooser(file, "Choose environment to open Helm chart", () -> open(source, false))
                    .showCenteredInCurrentWindow(source.getProject());
        } else HelmfileChartSourceService.get(source.getProject()).open(request);
    }
    static Request currentRequest(SmartPsiElementPointer<YAMLScalar> source) {
        return ReadAction.computeBlocking(() -> {
            YAMLScalar scalar = source.getElement();
            return scalar == null ? null : request(scalar);
        });
    }
    private HelmfileChartNavigation() {}
}
