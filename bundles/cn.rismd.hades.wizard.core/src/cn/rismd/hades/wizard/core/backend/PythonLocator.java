package cn.rismd.hades.wizard.core.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class PythonLocator {
    private static final Pattern VERSION = Pattern.compile("Python (3)\\.(\\d+)(?:\\.(\\d+))?");
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);

    public record PythonCommand(List<String> prefix, String description) {
        public PythonCommand {
            prefix = List.copyOf(prefix);
        }
    }

    public PythonCommand locate(String preference) throws BackendException {
        var candidates = candidates(preference, System.getProperty("os.name", "").toLowerCase().contains("win"));
        BackendException last = null;
        for (var candidate : candidates) {
            try {
                checkVersion(candidate);
                return candidate;
            } catch (BackendException e) {
                last = e;
                if (preference != null && !preference.isBlank()) {
                    break;
                }
            }
        }
        if (last != null) {
            throw last;
        }
        throw new BackendException("E_PYTHON", "No Python 3.11+ executable was found");
    }

    public List<PythonCommand> candidates(String preference, boolean windows) {
        var result = new ArrayList<PythonCommand>();
        if (preference != null && !preference.isBlank()) {
            result.add(new PythonCommand(List.of(preference), preference));
            return List.copyOf(result);
        }
        result.add(new PythonCommand(List.of("python3"), "python3"));
        result.add(new PythonCommand(List.of("python"), "python"));
        if (windows) {
            result.add(new PythonCommand(List.of("py", "-3"), "py -3"));
        }
        return List.copyOf(result);
    }

    public void checkVersion(PythonCommand candidate) throws BackendException {
        var command = new ArrayList<>(candidate.prefix());
        command.add("--version");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(VERSION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new BackendException("E_PYTHON_TIMEOUT", "Python version check timed out for " + candidate.description());
            }
            String output = new String(process.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            var matcher = VERSION.matcher(output.strip());
            if (process.exitValue() != 0 || !matcher.matches() || Integer.parseInt(matcher.group(2)) < 11) {
                throw new BackendException("E_PYTHON_VERSION",
                        candidate.description() + " must be Python 3.11 or newer: " + output.strip());
            }
        } catch (IOException e) {
            throw new BackendException("E_PYTHON_NOT_FOUND", "Cannot execute " + candidate.description(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException("E_PYTHON_INTERRUPTED", "Python version check interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public static boolean exists(Path executable) {
        return executable != null && Files.isRegularFile(executable) && Files.isExecutable(executable);
    }
}
