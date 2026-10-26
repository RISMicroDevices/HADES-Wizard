package cn.rismd.hades.wizard.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.swt.widgets.Display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import cn.rismd.hades.wizard.core.backend.BackendException;
import cn.rismd.hades.wizard.core.backend.BackendService;
import cn.rismd.hades.wizard.core.backend.Diagnostic;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderJson;

public final class BackendController implements AutoCloseable {
    @FunctionalInterface
    public interface StateListener {
        void stateChanged(BackendController controller);
    }

    private final ProviderDescriptor provider;
    private final Path descriptorPath;
    private final String pythonPreference;
    private final Display display;
    private final StateListener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "HADES-Wizard backend worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong revisions = new AtomicLong();
    private volatile BackendService service;
    private JsonObject config;
    private JsonObject analysis;
    private JsonObject module;
    private JsonArray depths = new JsonArray();
    private List<Diagnostic> diagnostics = List.of();
    private boolean valid;
    private boolean loading;
    private String backendError;
    private boolean automaticAddressing = true;
    private boolean automaticTiming;
    private int selectedPort;
    private Path output;
    private boolean force;
    private boolean fullCopy = true;
    private JsonObject previewResult;
    private long previewRevision = -1;
    private boolean previewFullCopy;

    public BackendController(ProviderDescriptor provider, Path descriptorPath, String pythonPreference, Display display,
            JsonObject initialConfig, boolean loaded, StateListener listener) {
        this.provider = provider;
        this.descriptorPath = descriptorPath;
        this.pythonPreference = pythonPreference;
        this.display = display;
        this.listener = listener;
        config = initialConfig == null ? null : initialConfig.deepCopy();
        automaticTiming = !loaded;
        automaticAddressing = !loaded;
    }

    public void start() {
        loading = true;
        notifyUi();
        executor.execute(() -> {
            try {
                service = BackendService.launch(provider, descriptorPath, pythonPreference, ProviderJson.gson(),
                        Duration.ofSeconds(30));
                JsonObject initial = config == null ? service.schema().getAsJsonObject("defaults") : config;
                long revision = revisions.get();
                JsonObject result = service.analyze(initial);
                accept(revision, result);
            } catch (BackendException e) {
                fail(revisions.get(), e);
            }
        });
    }

    public long revision() {
        return revisions.get();
    }

    public ProviderDescriptor provider() {
        return provider;
    }

    public JsonObject config() {
        return config == null ? null : config.deepCopy();
    }

    public JsonObject analysis() {
        return analysis == null ? null : analysis.deepCopy();
    }

    public JsonObject module() {
        return module == null ? null : module.deepCopy();
    }

    public JsonArray depths() {
        return depths.deepCopy();
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean valid() {
        return valid && backendError == null;
    }

    public boolean loading() {
        return loading;
    }

    public String backendError() {
        return backendError;
    }

    public boolean automaticAddressing() {
        return automaticAddressing;
    }

    public boolean automaticTiming() {
        return automaticTiming;
    }

    public int selectedPort() {
        return selectedPort;
    }

    public void selectPort(int index) {
        selectedPort = Math.max(0, index);
        notifyUi();
    }

    public Path output() {
        return output;
    }

    public void output(Path value) {
        output = value;
        notifyUi();
    }

    public boolean force() {
        return force;
    }

    public void force(boolean value) {
        force = value;
        notifyUi();
    }

    public boolean fullCopy() {
        return fullCopy;
    }

    public void fullCopy(boolean value) {
        if (fullCopy == value) {
            return;
        }
        fullCopy = value;
        previewResult = null;
        previewRevision = -1;
        if (config != null) {
            loading = true;
            notifyUi();
            executeAnalysis(revisions.get(), config.deepCopy(), "analyze");
        } else {
            notifyUi();
        }
    }

    public JsonObject previewResult() {
        return previewResult == null ? null : previewResult.deepCopy();
    }

    public boolean previewCurrent() {
        return previewResult != null && previewRevision == revisions.get() && previewFullCopy == fullCopy;
    }

    public void editGlobal(String key, JsonElement value) {
        if (config == null) {
            return;
        }
        var snapshot = config.deepCopy();
        snapshot.add(key, value);
        long revision = edit(snapshot);
        executeAnalysis(revision, snapshot, "analyze");
    }

    public void editPort(int index, String key, JsonElement value) {
        if (config == null) {
            return;
        }
        var snapshot = config.deepCopy();
        var ports = snapshot.getAsJsonArray("ports");
        if (index < 0 || index >= ports.size()) {
            return;
        }
        var port = ports.get(index).getAsJsonObject();
        if (!key.equals("depth")) {
            port.add(key, value);
        }
        if (key.equals("width") || key.equals("depth")) {
            var nextDepths = depths.deepCopy();
            ensureSize(nextDepths, ports.size());
            if (key.equals("depth")) {
                nextDepths.set(index, value);
            }
            long revision = edit(snapshot);
            executor.execute(() -> {
                try {
                    JsonObject result;
                    if (automaticAddressing) {
                        result = service.allocate(snapshot, nextDepths);
                    } else {
                        var changes = new JsonObject();
                        changes.add(Integer.toString(index), nextDepths.get(index));
                        result = service.applyDepths(snapshot, changes);
                    }
                    accept(revision, result);
                } catch (BackendException e) {
                    fail(revision, e);
                }
            });
        } else {
            long revision = edit(snapshot);
            executeAnalysis(revision, snapshot, "analyze");
        }
    }

    public void resizePorts(int count) {
        if (config == null) {
            return;
        }
        var snapshot = config.deepCopy();
        long revision = edit(snapshot);
        executor.execute(() -> {
            try {
                JsonObject resized = service.resize(snapshot, count);
                JsonObject result = resized;
                if (automaticAddressing && resized.has("depths") && !resized.get("depths").isJsonNull()) {
                    result = service.allocate(resized.getAsJsonObject("config"), resized.getAsJsonArray("depths"));
                }
                accept(revision, result);
            } catch (BackendException e) {
                fail(revision, e);
            }
        });
    }

    public void addressingMode(boolean automatic) {
        if (automaticAddressing == automatic) {
            return;
        }
        automaticAddressing = automatic;
        if (!automatic || config == null) {
            notifyUi();
            return;
        }
        var snapshot = config.deepCopy();
        var retainedDepths = depths.deepCopy();
        long revision = edit(snapshot);
        executor.execute(() -> {
            try {
                accept(revision, service.allocate(snapshot, retainedDepths));
            } catch (BackendException e) {
                fail(revision, e);
            }
        });
    }

    public void frequency(JsonElement value) {
        if (config == null) {
            return;
        }
        var snapshot = config.deepCopy();
        snapshot.add("port_clock_hz", value);
        long revision = edit(snapshot);
        executeAnalysis(revision, snapshot, automaticTiming ? "derive_periods" : "analyze");
    }

    public void timingMode(boolean automatic) {
        automaticTiming = automatic;
        if (automatic) {
            derivePeriods();
        } else {
            notifyUi();
        }
    }

    public void timingValue(String key, JsonElement value) {
        automaticTiming = false;
        editGlobal(key, value);
    }

    public void derivePeriods() {
        if (config == null) {
            return;
        }
        var snapshot = config.deepCopy();
        long revision = edit(snapshot);
        executeAnalysis(revision, snapshot, "derive_periods");
    }

    public void loadPreview() {
        if (config == null) {
            return;
        }
        var snapshot = config.deepCopy();
        long revision = revisions.get();
        boolean requestedFullCopy = fullCopy;
        loading = true;
        notifyUi();
        executor.execute(() -> {
            try {
                JsonObject result = service.preview(snapshot, requestedFullCopy);
                display.asyncExec(() -> {
                    if (revision == revisions.get()) {
                        if (requestedFullCopy == fullCopy) {
                            previewResult = result;
                            previewRevision = revision;
                            previewFullCopy = requestedFullCopy;
                        }
                        acceptState(result);
                        loading = false;
                        notifyUi();
                    }
                });
            } catch (BackendException e) {
                fail(revision, e);
            }
        });
    }

    public JsonObject saveSnapshot(Path path, boolean force) throws BackendException {
        return service.save(config.deepCopy(), path, force);
    }

    public JsonObject generateSnapshot(Path destination, boolean force) throws BackendException {
        JsonObject snapshot = config.deepCopy();
        JsonObject validated = service.analyze(snapshot);
        if (!isValid(validated)) {
            accept(revisions.get(), validated);
            return null;
        }
        return service.generate(validated.getAsJsonObject("config"), destination, false, force, fullCopy);
    }

    public void restart() {
        if (service != null) {
            service.close();
        }
        backendError = null;
        loading = true;
        long revision = revisions.get();
        notifyUi();
        executor.execute(() -> {
            try {
                service = BackendService.launch(provider, descriptorPath, pythonPreference, ProviderJson.gson(),
                        Duration.ofSeconds(30));
                accept(revision, service.analyze(config));
            } catch (BackendException e) {
                fail(revision, e);
            }
        });
    }

    private void executeAnalysis(long revision, JsonObject snapshot, String operation) {
        executor.execute(() -> {
            try {
                JsonObject result = switch (operation) {
                    case "derive_periods" -> service.derivePeriods(snapshot);
                    default -> service.analyze(snapshot);
                };
                accept(revision, result);
            } catch (BackendException e) {
                fail(revision, e);
            }
        });
    }

    private long edit(JsonObject snapshot) {
        long revision = revisions.incrementAndGet();
        config = snapshot;
        module = null;
        previewResult = null;
        previewRevision = -1;
        loading = true;
        notifyUi();
        return revision;
    }

    private void accept(long revision, JsonObject result) {
        display.asyncExec(() -> {
            if (revision == revisions.get()) {
                acceptState(result);
                loading = false;
                backendError = null;
                notifyUi();
            }
        });
    }

    private void acceptState(JsonObject result) {
        valid = isValid(result);
        diagnostics = parseDiagnostics(result.getAsJsonArray("diagnostics"));
        if (result.has("config") && !result.get("config").isJsonNull()) {
            config = result.getAsJsonObject("config").deepCopy();
            if (selectedPort >= config.getAsJsonArray("ports").size()) {
                selectedPort = config.getAsJsonArray("ports").size() - 1;
            }
        }
        analysis = result.has("analysis") && !result.get("analysis").isJsonNull()
                ? result.getAsJsonObject("analysis").deepCopy() : null;
        module = result.has("module") && !result.get("module").isJsonNull()
                ? result.getAsJsonObject("module").deepCopy() : null;
        if (result.has("depths") && !result.get("depths").isJsonNull()) {
            depths = result.getAsJsonArray("depths").deepCopy();
        }
    }

    private void fail(long revision, BackendException error) {
        display.asyncExec(() -> {
            if (revision == revisions.get()) {
                loading = false;
                valid = false;
                module = null;
                backendError = error.getMessage();
                diagnostics = error.diagnostics();
                notifyUi();
            }
        });
    }

    private void notifyUi() {
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!display.isDisposed()) {
                    listener.stateChanged(this);
                }
            });
        }
    }

    public static boolean isValid(JsonObject result) {
        if (!result.has("valid") || result.get("valid").isJsonNull() || !result.get("valid").getAsBoolean()) {
            return false;
        }
        return parseDiagnostics(result.getAsJsonArray("diagnostics")).stream()
                .noneMatch(diagnostic -> diagnostic.severity().equalsIgnoreCase("error"));
    }

    public static List<Diagnostic> parseDiagnostics(JsonArray array) {
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

    public static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "—";
        }
        var value = object.get(key);
        return value.getAsJsonPrimitive().isBoolean() ? Boolean.toString(value.getAsBoolean()) : value.getAsString();
    }

    public static JsonElement jsonValue(String value, boolean numeric) {
        if (numeric) {
            try {
                return new com.google.gson.JsonPrimitive(Long.parseLong(value));
            } catch (NumberFormatException e) {
                return new com.google.gson.JsonPrimitive(value);
            }
        }
        return new com.google.gson.JsonPrimitive(value);
    }

    private static void ensureSize(JsonArray array, int size) {
        while (array.size() < size) {
            array.add(JsonNull.INSTANCE);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        if (service != null) {
            service.close();
        }
    }
}
