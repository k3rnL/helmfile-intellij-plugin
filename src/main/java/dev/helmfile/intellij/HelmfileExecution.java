package dev.helmfile.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

final class HelmfileExecution {
    static String protectionProblem(String regex) {
        try { Pattern.compile(regex); return null; }
        catch (PatternSyntaxException error) { return "Invalid protected environments regex in Settings | Tools | Helmfile: " + error.getDescription(); }
    }
    static boolean protectedEnvironment(String regex, String environment) { return !regex.isEmpty() && Pattern.matches(regex, environment); }

    static ProcessHandler start(Project project, HelmfileCommand command, Map<String, String> variables, boolean passParent) throws ExecutionException {
        String problem = command.problem();
        if (problem != null) throw new ExecutionException(problem);
        HelmfileSettings settings = HelmfileSettings.get(project);
        String regex = settings.protectedEnvironments();
        problem = protectionProblem(regex);
        if (problem != null) throw new ExecutionException(problem);
        GeneralCommandLine line = command.commandLine(settings.executable(), variables, passParent);
        if (protectedEnvironment(regex, command.environment())) {
            AtomicBoolean confirmed = new AtomicBoolean();
            Runnable ask = () -> {
                if (project.isDisposed()) return;
                String message = "Environment '" + command.environment() + "' is protected by '" + regex + "'.\n\n"
                        + "Action: " + command.operation().label + "\nRelease selector: " + command.selector()
                        + "\nState file: " + command.file() + "\n\nRun this action?";
                confirmed.set(Messages.showDialog(project, message, "Protected Helmfile Environment",
                        new String[]{"Run " + command.operation().label, "Cancel"}, 1, Messages.getWarningIcon()) == 0);
            };
            Application application = ApplicationManager.getApplication();
            if (application.isDispatchThread()) ask.run();
            else application.invokeAndWait(ask, ModalityState.defaultModalityState());
            if (!confirmed.get()) throw new com.intellij.openapi.progress.ProcessCanceledException();
        }
        if (project.isDisposed()) throw new com.intellij.openapi.progress.ProcessCanceledException();
        KillableColoredProcessHandler handler = new KillableColoredProcessHandler(line);
        ProcessTerminatedListener.attach(handler, project);
        return handler;
    }
    private HelmfileExecution() {}
}
