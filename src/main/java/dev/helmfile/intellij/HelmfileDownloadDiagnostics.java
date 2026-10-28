package dev.helmfile.intellij;

import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.ui.*;
import com.intellij.notification.*;
import com.intellij.openapi.project.Project;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Readable download failures without exposing authentication in notifications or console output. */
final class HelmfileDownloadDiagnostics {
    private static final int OUTPUT_LIMIT = 32_768;
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern USER_INFO = Pattern.compile("(?i)(\\b(?:https?|oci)://)[^\\s/@]+@");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?im)(\\b(?:proxy-)?authorization\\s*:\\s*)(?:Basic|Bearer)\\s+[^\\s]+");
    private static final Pattern SECRET_PARAMETER = Pattern.compile("(?i)((?:[?&]|\\b)(?:access[_-]?token|private[_-]?token|auth[_-]?token|token|password|passwd|api[_-]?key|client[_-]?secret)[\"']?\\s*(?:=|:)\\s*[\"']?)[^\\s&\"'<>]+");
    private static final Pattern AUTH_ARGUMENT = Pattern.compile("(?i)(--(?:username|password)(?:=|\\s+))(?:\"[^\"]*\"|'[^']*'|\\S+)");

    static String clean(String text, HelmfileRepositories.Access access) {
        String result = ANSI.matcher(text == null ? "" : text).replaceAll("").replaceAll("[\\p{Cc}&&[^\\n\\r\\t]]", "");
        if (access != null && !access.password().isEmpty()) {
            String password = access.password();
            String encoded = URLEncoder.encode(password, StandardCharsets.UTF_8);
            String basic = Base64.getEncoder().encodeToString((access.username() + ":" + password).getBytes(StandardCharsets.UTF_8));
            for (String secret : List.of(basic, encoded.replace("+", "%20"), encoded, password)) result = result.replace(secret, "[redacted]");
        }
        result = USER_INFO.matcher(result).replaceAll("$1[redacted]@");
        result = AUTHORIZATION.matcher(result).replaceAll("$1[redacted]");
        result = SECRET_PARAMETER.matcher(result).replaceAll("$1[redacted]");
        result = AUTH_ARGUMENT.matcher(result).replaceAll("$1[redacted]");
        result = result.strip();
        if (result.length() > OUTPUT_LIMIT) {
            String marker = "\n[Output truncated; first and last sections shown]\n";
            int first = (OUTPUT_LIMIT - marker.length()) / 2;
            result = result.substring(0, first) + marker + result.substring(result.length() - (OUTPUT_LIMIT - marker.length() - first));
        }
        return result;
    }

    static String reason(Throwable error, HelmfileRepositories.Access access) {
        List<String> messages = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.size() < 4 && seen.add(cause); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && !message.isBlank() && !messages.contains(message)) messages.add(message);
        }
        return clean(messages.isEmpty() ? error.getClass().getSimpleName() : String.join(": ", messages), access);
    }

    static String processFailure(String status, ProcessOutput output, HelmfileRepositories.Access access) {
        String stderr = clean(output.getStderr(), access), stdout = clean(output.getStdout(), access);
        String source = stderr.isBlank() ? stdout : stderr;
        String reason = java.util.stream.Stream.concat(stderr.lines(), stdout.lines()).map(String::strip)
                .filter(line -> line.regionMatches(true, 0, "Error:", 0, 6))
                .findFirst().orElseGet(() -> source.lines().filter(line -> !line.isBlank()).findFirst().orElse("Helm produced no output."));
        return status + ": " + preview(reason)
                + (stderr.isBlank() ? "" : "\n\nHelm standard error:\n" + stderr)
                + (stdout.isBlank() ? "" : "\n\nHelm standard output:\n" + stdout);
    }

    static String preview(String message) {
        String firstLine = message.lines().findFirst().orElse(message);
        return firstLine.length() <= 600 ? firstLine : firstLine.substring(0, 600) + "…";
    }

    static void chartFailure(Project project, HelmfileChartNavigation.Request request, String environment, String message) {
        String version = request.version().isBlank() ? "latest" : request.version();
        String identity = clean(request.chart() + " · " + version + " · environment " + environment, request.access());
        String endpoint = request.access() == null ? "" : request.access().url();
        if (request.access() != null && request.access().oci() && !endpoint.contains("://")) endpoint = "oci://" + endpoint;
        String context = "Chart: " + request.chart() + "\nVersion: " + version + "\nEnvironment: " + environment
                + (endpoint.isBlank() ? "" : "\nRepository: " + endpoint);
        String safe = clean(message, request.access());
        notify(project, "Cannot open Helm chart", preview(safe) + "\n" + identity,
                clean(context, request.access()) + "\n\n" + safe);
    }

    static void notify(Project project, String title, String summary, String details) {
        if (project.isDisposed()) return;
        NotificationGroupManager.getInstance().getNotificationGroup("Helmfile repositories")
                .createNotification(title, FunctionCatalog.escape(summary).replace("\n", "<br>"), NotificationType.WARNING)
                .addAction(NotificationAction.createSimple("Show Details", () -> showDetails(project, title, details))).notify(project);
    }

    private static void showDetails(Project project, String title, String details) {
        if (project.isDisposed()) return;
        ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        RunContentDescriptor descriptor = new RunContentDescriptor(console, null, console.getComponent(), title);
        console.print(details + "\n", ConsoleViewContentType.ERROR_OUTPUT);
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
    }

    private HelmfileDownloadDiagnostics() {}
}
