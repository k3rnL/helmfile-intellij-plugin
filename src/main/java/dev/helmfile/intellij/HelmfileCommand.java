package dev.helmfile.intellij;

import com.intellij.execution.configurations.GeneralCommandLine;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/** One explicit environment and one exact release selector; arguments never pass through a shell. */
record HelmfileCommand(String file, String release, String namespace, String environment, HelmfileOperation operation) {
    String problem() {
        if (file == null || file.isBlank()) return "Choose a Helmfile state file.";
        try {
            if (!Path.of(file).isAbsolute() || !Files.isRegularFile(Path.of(file))) return "The Helmfile state file must be an existing absolute file path.";
        } catch (InvalidPathException error) { return "The Helmfile state file path is invalid."; }
        // Commas and operators have selector semantics, even without a shell.
        if (release == null || !release.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) return "Enter the rendered release name (letters, numbers, '.', '_' or '-').";
        if (namespace == null || !namespace.isEmpty() && !namespace.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) return "Enter a literal namespace or leave it empty.";
        if (environment == null || environment.isBlank() || !environment.equals(environment.strip()) || environment.chars().anyMatch(Character::isISOControl)) return "Enter an environment name without surrounding whitespace or control characters.";
        if (operation == null) return "Choose a Helmfile command.";
        return null;
    }
    String selector() { return "name=" + release + (namespace.isEmpty() ? "" : ",namespace=" + namespace); }
    GeneralCommandLine commandLine(String executable, Map<String, String> variables, boolean passParent) {
        return new GeneralCommandLine().withExePath(executable).withWorkDirectory(Path.of(file).getParent().toFile())
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(passParent ? GeneralCommandLine.ParentEnvironmentType.CONSOLE : GeneralCommandLine.ParentEnvironmentType.NONE)
                .withEnvironment(variables)
                .withParameters("--file", file, "--environment", environment, "--selector", selector(), "--interactive=false", operation.command());
    }
}
