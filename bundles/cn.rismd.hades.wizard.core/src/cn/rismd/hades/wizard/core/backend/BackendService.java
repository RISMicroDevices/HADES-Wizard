package cn.rismd.hades.wizard.core.backend;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;

public final class BackendService implements AutoCloseable {
    public static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    public static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final int STDERR_LIMIT = 64 * 1024;

    private final Process process;
    private final OutputStream stdin;
    private final BufferedInputStream stdout;
    private final Gson gson;
    private final Duration timeout;
    private final Object requestLock = new Object();
    private final AtomicLong nextId = new AtomicLong(1);
    private final LinkedBlockingQueue<ReaderEvent> responses = new LinkedBlockingQueue<>();
    private final StringBuilder stderr = new StringBuilder();
    private final Thread readerThread;
    private final Thread stderrThread;
    private volatile boolean closed;
    private JsonObject schema;

    private record ReaderEvent(JsonObject response, BackendException failure) {
    }

    private BackendService(Process process, Gson gson, Duration timeout) {
        this.process = process;
        this.gson = gson;
        this.timeout = timeout;
        stdin = process.getOutputStream();
        stdout = new BufferedInputStream(process.getInputStream());
        readerThread = new Thread(this::readResponses, "HADES-Wizard protocol reader");
        readerThread.setDaemon(true);
        readerThread.start();
        stderrThread = new Thread(this::drainStderr, "HADES-Wizard backend stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    public static BackendService launch(ProviderDescriptor provider, Path descriptorPath, String pythonPreference,
            Gson gson, Duration timeout) throws BackendException {
        if (provider.backend() == null) {
            throw new BackendException("E_BACKEND", "Provider has no backend descriptor");
        }
        Path launcher = descriptorPath.toAbsolutePath().getParent().resolve(provider.backend().entryPoint()).normalize();
        var python = new PythonLocator().locate(pythonPreference);
        var command = new ArrayList<>(python.prefix());
        command.add(launcher.toString());
        command.add("protocol");
        try {
            Process process = builder(command, launcher.getParent()).start();
            BackendService service = new BackendService(process, gson, timeout);
            service.initialize(provider);
            return service;
        } catch (IOException e) {
            throw new BackendException("E_BACKEND_START", "Cannot start backend " + launcher, e);
        }
    }

    private static ProcessBuilder builder(List<String> command, Path directory) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(false);
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        return builder;
    }

    private void initialize(ProviderDescriptor provider) throws BackendException {
        try {
            schema = call("schema", new JsonObject());
            if (schema.get("protocol_version").getAsInt() != provider.protocolVersion()
                    || schema.get("schema_version").getAsInt() != provider.schemaVersion()
                    || !provider.version().toString().equals(schema.get("version").getAsString())
                    || !provider.vlnv().equals(schema.get("vlnv").getAsString())) {
                close();
                throw new BackendException("E_HANDSHAKE", "Backend schema/protocol/version/VLNV does not match provider");
            }
        } catch (BackendException | RuntimeException e) {
            close();
            if (e instanceof BackendException backendException) {
                throw backendException;
            }
            throw new BackendException("E_HANDSHAKE", "Malformed backend schema response", e);
        }
    }

    public JsonObject schema() {
        return schema.deepCopy();
    }

    public JsonObject analyze(JsonObject config) throws BackendException {
        return withPayload("analyze", "config", config);
    }

    public JsonObject analyzeText(String text) throws BackendException {
        return withPayload("analyze_text", "text", new com.google.gson.JsonPrimitive(text));
    }

    public JsonObject derivePeriods(JsonObject config) throws BackendException {
        return withPayload("derive_periods", "config", config);
    }

    public JsonObject resize(JsonObject config, int count) throws BackendException {
        var payload = new JsonObject();
        payload.add("config", config);
        payload.addProperty("count", count);
        return call("resize", payload);
    }

    public JsonObject applyDepths(JsonObject config, JsonObject changes) throws BackendException {
        var payload = new JsonObject();
        payload.add("config", config);
        payload.add("changes", changes);
        return call("apply_depths", payload);
    }

    public JsonObject allocate(JsonObject config, JsonArray depths) throws BackendException {
        var payload = new JsonObject();
        payload.add("config", config);
        payload.add("depths", depths);
        return call("allocate", payload);
    }

    public JsonObject preview(JsonObject config) throws BackendException {
        return preview(config, true);
    }

    public JsonObject preview(JsonObject config, boolean fullCopy) throws BackendException {
        var payload = new JsonObject();
        payload.add("config", config);
        payload.addProperty("full_copy", fullCopy);
        return call("preview", payload);
    }

    public JsonObject save(JsonObject config, Path path, boolean force) throws BackendException {
        var payload = new JsonObject();
        payload.add("config", config);
        payload.addProperty("path", path.toString());
        payload.addProperty("force", force);
        return call("save", payload);
    }

    public JsonObject generate(JsonObject config, Path output, boolean check, boolean force) throws BackendException {
        return generate(config, output, check, force, true);
    }

    public JsonObject generate(JsonObject config, Path output, boolean check, boolean force, boolean fullCopy)
            throws BackendException {
        var payload = new JsonObject();
        payload.add("config", config);
        payload.addProperty("output", output.toString());
        payload.addProperty("check", check);
        payload.addProperty("force", force);
        payload.addProperty("full_copy", fullCopy);
        return call("generate", payload);
    }

    private JsonObject withPayload(String operation, String name, JsonElement value) throws BackendException {
        var payload = new JsonObject();
        payload.add(name, value);
        return call(operation, payload);
    }

    public JsonObject call(String operation, JsonObject payload) throws BackendException {
        synchronized (requestLock) {
            if (closed || !process.isAlive()) {
                throw new BackendException("E_BACKEND_CLOSED", "Backend process is not running");
            }
            long id = nextId.getAndIncrement();
            var request = payload.deepCopy();
            request.addProperty("protocol_version", 1);
            request.addProperty("id", id);
            request.addProperty("operation", operation);
            byte[] frame;
            try {
                frame = (gson.toJson(request) + "\n").getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                throw new BackendException("E_REQUEST_ENCODING", "Could not encode request", e);
            }
            if (frame.length > MAX_REQUEST_BYTES) {
                throw new BackendException("E_REQUEST_TOO_LARGE", "Request exceeds 2 MiB");
            }
            try {
                stdin.write(frame);
                stdin.flush();
                ReaderEvent event = responses.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (event == null) {
                    close();
                    throw new BackendException("E_TIMEOUT", "Backend response timed out");
                }
                if (event.failure != null) {
                    close();
                    throw event.failure;
                }
                JsonObject response = event.response;
                if (response.get("protocol_version") == null || response.get("protocol_version").getAsInt() != 1
                        || response.get("id") == null || response.get("id").getAsLong() != id) {
                    close();
                    throw new BackendException("E_RESPONSE_ID", "Late or mismatched backend response id");
                }
                if (!response.get("ok").getAsBoolean()) {
                    var error = response.getAsJsonObject("error");
                    throw new BackendException(error.get("code").getAsString(), error.get("message").getAsString(),
                            diagnostics(error.getAsJsonArray("diagnostics")));
                }
                return response.getAsJsonObject("result");
            } catch (IOException e) {
                close();
                throw new BackendException("E_BACKEND_IO", "Backend I/O failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                throw new BackendException("E_INTERRUPTED", "Backend request interrupted", e);
            } catch (RuntimeException e) {
                close();
                throw new BackendException("E_RESPONSE_MALFORMED", "Malformed backend response", e);
            }
        }
    }

    private void readResponses() {
        try {
            while (!closed) {
                byte[] line = readLine();
                if (line == null) {
                    responses.offer(new ReaderEvent(null,
                            new BackendException("E_BACKEND_EOF", "Backend closed stdout unexpectedly")));
                    return;
                }
                JsonObject response = JsonParser.parseString(decode(line)).getAsJsonObject();
                responses.offer(new ReaderEvent(response, null));
            }
        } catch (Exception e) {
            responses.offer(new ReaderEvent(null, new BackendException("E_RESPONSE_MALFORMED",
                    "Malformed or oversized backend response: " + e.getMessage(), e)));
        }
    }

    private byte[] readLine() throws IOException, BackendException {
        var output = new ByteArrayOutputStream();
        while (true) {
            int value = stdout.read();
            if (value < 0) {
                if (output.size() == 0) {
                    return null;
                }
                throw new BackendException("E_RESPONSE_MALFORMED", "Unterminated response frame");
            }
            if (value == '\n') {
                return output.toByteArray();
            }
            if (output.size() >= MAX_RESPONSE_BYTES) {
                throw new BackendException("E_RESPONSE_TOO_LARGE", "Response exceeds 32 MiB");
            }
            output.write(value);
        }
    }

    private static BackendException throwMalformed(String message) throws BackendException {
        throw new BackendException("E_RESPONSE_MALFORMED", message);
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }

    private static List<Diagnostic> diagnostics(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        var result = new ArrayList<Diagnostic>();
        for (var item : array) {
            var object = item.getAsJsonObject();
            result.add(new Diagnostic(text(object, "severity"), text(object, "code"), text(object, "path"),
                    text(object, "message")));
        }
        return List.copyOf(result);
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private void drainStderr() {
        try {
            byte[] buffer = new byte[4096];
            while (true) {
                int count = process.getErrorStream().read(buffer);
                if (count < 0) {
                    return;
                }
                synchronized (stderr) {
                    if (stderr.length() < STDERR_LIMIT) {
                        stderr.append(new String(buffer, 0, Math.min(count, STDERR_LIMIT - stderr.length()),
                                StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException ignored) {
            // Process close terminates the bounded stderr drainer.
        }
    }

    public String stderr() {
        synchronized (stderr) {
            return stderr.toString();
        }
    }

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    public void cancel() {
        close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
