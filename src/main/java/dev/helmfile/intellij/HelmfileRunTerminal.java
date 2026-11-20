package dev.helmfile.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.PtyCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.io.IOUtil;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** A controlling terminal for helpers, with Helmfile stdout on its own pipe for manifest viewers. */
record HelmfileRunTerminal(Process process, InputStream stdout) {
    private static final Logger LOG = Logger.getInstance(HelmfileRunTerminal.class);
    static boolean supported() { return SystemInfo.isLinux || SystemInfo.isMac; }

    static HelmfileRunTerminal start(GeneralCommandLine line) throws ExecutionException {
        if (!supported()) return new HelmfileRunTerminal(line.createProcess(), null);

        // The terminal carries stdin, stderr and /dev/tty. Only stdout goes through the FIFO.
        // A temporary file here would put rendered Secret manifests on disk.
        Path directory = null;
        FileChannel guard = null;
        InputStream stdout = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("helmfile-run-");
            Path pipe = directory.resolve("stdout");
            var mkfifo = new CapturingProcessHandler(new GeneralCommandLine("mkfifo", pipe.toString())).runProcess();
            if (mkfifo.getExitCode() != 0) throw new IOException("Cannot create the Helmfile output pipe: " + mkfifo.getStderr());

            // Opening either end alone blocks until its peer opens. Keep both ends available
            // through startup, even if the child is stopped before it redirects stdout.
            guard = FileChannel.open(pipe, StandardOpenOption.READ, StandardOpenOption.WRITE);
            stdout = new FileInputStream(pipe.toFile());
            var terminal = new PtyCommandLine(line).withConsoleMode(false);
            terminal.withExePath("/bin/sh").getParametersList().prependAll("-c", "exec \"$@\" >\"$0\"", pipe.toString(), line.getExePath());
            process = terminal.withRedirectErrorStream(true).createProcess();
            FileChannel writer = guard;
            Path runDirectory = directory;
            process.onExit().thenRun(() -> {
                IOUtil.closeSafe(LOG, writer);
                FileUtil.delete(runDirectory.toFile());
            });
            return new HelmfileRunTerminal(process, stdout);
        } catch (IOException error) {
            throw new ExecutionException("Cannot open the Helmfile output pipe: " + error.getMessage(), error);
        } finally {
            if (process == null) {
                IOUtil.closeSafe(LOG, stdout, guard);
                if (directory != null) FileUtil.delete(directory.toFile());
            }
        }
    }
}
