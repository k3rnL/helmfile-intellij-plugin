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
        return start(project, command, variables, passParent, false);
    }
    static ProcessHandler start(Project project, HelmfileCommand command, Map<String, String> variables, boolean passParent, boolean approvedApply) throws ExecutionException {
        String problem = command.problem();
        if (problem != null) throw new ExecutionException(problem);
        HelmfileSettings settings = HelmfileSettings.get(project);
        String regex = settings.protectedEnvironments();
        problem = protectionProblem(regex);
        if (problem != null) throw new ExecutionException(problem);
        boolean reviewing = command.operation() == HelmfileOperation.APPLY && !approvedApply;
        HelmfileCommand executed = reviewing ? command.withOperation(HelmfileOperation.DIFF) : command;
        GeneralCommandLine line = executed.commandLine(settings.executable(), variables, passParent);
        if (protectedEnvironment(regex, command.environment())) {
            AtomicBoolean confirmed = new AtomicBoolean();
            Runnable ask = () -> {
                if (project.isDisposed()) return;
                String message = "Environment '" + command.environment() + "' is protected by '" + regex + "'.\n\n"
                        + "Action: " + command.operation().label + "\nScope: " + command.scope()
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
        HelmfileApplyReview review = null;
        if (reviewing) {
            try { review = new HelmfileApplyReview(project, command, variables, passParent); }
            catch (java.io.IOException error) { throw new ExecutionException("Cannot read the Helmfile for review", error); }
        }
        HelmfileDiffRun diff = executed.operation() == HelmfileOperation.DIFF ? new HelmfileDiffRun(project, executed, review) : null;
        KillableColoredProcessHandler handler = new KillableColoredProcessHandler(line) {
            @Override protected void destroyProcessImpl() {
                if (diff != null) diff.cancel();
                super.destroyProcessImpl();
            }
            @Override protected void detachProcessImpl() {
                if (diff != null) diff.cancel();
                super.detachProcessImpl();
            }
            @Override protected void notifyProcessTerminated(int exitCode) {
                // With --detailed-exitcode, 2 means a successful diff which found changes.
                if (executed.operation() == HelmfileOperation.DIFF) putUserData(HelmfileDiffRun.EXIT_CODE, exitCode);
                super.notifyProcessTerminated(executed.operation() == HelmfileOperation.DIFF && exitCode == 2 ? 0 : exitCode);
            }
        };
        if (diff != null) handler.addProcessListener(diff);
        ProcessTerminatedListener.attach(handler, project);
        return handler;
    }
    private HelmfileExecution() {}
}
