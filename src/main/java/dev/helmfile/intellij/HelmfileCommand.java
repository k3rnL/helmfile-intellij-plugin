package dev.helmfile.intellij;

import com.intellij.execution.configurations.GeneralCommandLine;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** One explicit environment and release scope. Arguments never pass through a shell. */
record HelmfileCommand(String file, String release, String namespace, String environment, HelmfileOperation operation) {
    HelmfileCommand withOperation(HelmfileOperation replacement) { return new HelmfileCommand(file, release, namespace, environment, replacement); }
    String problem() {
        if (file == null || file.isBlank()) return "Choose a Helmfile state file.";
        try {
            if (!Path.of(file).isAbsolute() || !Files.isRegularFile(Path.of(file))) return "The Helmfile state file must be an existing absolute file path.";
        } catch (InvalidPathException error) { return "The Helmfile state file path is invalid."; }
        // Commas and operators have selector semantics, even without a shell.
        if (release == null || !wholeState() && !release.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) return "Enter the rendered release name (letters, numbers, '.', '_' or '-').";
        if (namespace == null || !namespace.isEmpty() && !namespace.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) return "Enter a literal namespace or leave it empty.";
        if (wholeState() && !namespace.isEmpty()) return "A full Helmfile command cannot have a release namespace selector.";
        if (environment == null || environment.isBlank() || !environment.equals(environment.strip()) || environment.chars().anyMatch(Character::isISOControl)) return "Enter an environment name without surrounding whitespace or control characters.";
        if (operation == null) return "Choose a Helmfile command.";
        return null;
    }
    boolean wholeState() { return "".equals(release); }
    String selector() { return wholeState() ? "" : "name=" + release + (namespace.isEmpty() ? "" : ",namespace=" + namespace); }
    String scope() { return wholeState() ? "All releases" : selector(); }
    GeneralCommandLine commandLine(String executable, Map<String, String> variables, boolean passParent) {
        GeneralCommandLine line = new GeneralCommandLine().withExePath(executable).withWorkDirectory(Path.of(file).getParent().toFile())
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(passParent ? GeneralCommandLine.ParentEnvironmentType.CONSOLE : GeneralCommandLine.ParentEnvironmentType.NONE)
                .withEnvironment(variables)
                .withParameters("--file", file, "--environment", environment);
        if (!wholeState()) line.addParameters("--selector", selector());
        line.addParameter("--interactive=false");
        if (operation == HelmfileOperation.DIFF) {
            // Full, uncolored context lets the IDE reconstruct both YAML documents. Serial output
            // keeps resources from different releases and nested states from interleaving.
            line.addParameters("--no-color", "diff", "--context", "-1", "--output", "diff", "--detailed-exitcode", "--concurrency", "1");
            line.withEnvironment("HELM_DIFF_COLOR", "false").withEnvironment("HELM_DIFF_OUTPUT_CONTEXT", "-1")
                    .withEnvironment("HELM_DIFF_TOOL", "");
        } else if (operation == HelmfileOperation.TEMPLATE) line.addParameters("--no-color", "template", "--concurrency", "1");
        else line.addParameter(operation.command());
        return line;
    }
}
