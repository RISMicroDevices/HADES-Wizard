package cn.rismd.hades.wizard.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.swt.custom.SashForm;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.window.ApplicationWindow;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonObject;

import cn.rismd.hades.wizard.core.backend.BackendException;
import cn.rismd.hades.wizard.core.backend.BackendService;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderException;
import cn.rismd.hades.wizard.core.provider.ProviderJson;
import cn.rismd.hades.wizard.modulepreview.ModulePortPreview;

public final class BackendWindow extends ApplicationWindow {
    private static final long MAX_CONFIG_BYTES = 2L * 1024 * 1024;
    private final ProviderDescriptor provider;
    private final Path descriptorPath;
    private final String pythonPreference;
    private final Path outputDirectory;
    private final ConfigFileChooser fileChooser;
    private final DirectoryChooser directoryChooser;
    private JsonObject configuration;
    private JsonObject module;
    private Path currentFile;
    private boolean dirty;
    private boolean configurationValid;
    private long configurationRevision;
    private Text summary;
    private ModulePortPreview modulePreview;
    private BackendWizard activeWizard;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "HADES-Wizard window backend");
        thread.setDaemon(true);
        return thread;
    });
    private volatile BackendService activeBackend;
    private long operationRevision;
    private volatile boolean busy;

    public BackendWizard activeWizard() {
        return activeWizard;
    }

    public boolean busy() {
        return busy;
    }

    public JsonObject configuration() {
        return configuration == null ? null : configuration.deepCopy();
    }

    public ModulePortPreview modulePreview() {
        return modulePreview;
    }

    public JsonObject module() {
        return module == null ? null : module.deepCopy();
    }

    public BackendWindow(ProviderDescriptor provider, Path descriptorPath, String pythonPreference, Path outputDirectory) {
        this(provider, descriptorPath, pythonPreference, outputDirectory, ConfigFileChooser.nativeDialogs(),
                DirectoryChooser.nativeDialogs());
    }

    public BackendWindow(ProviderDescriptor provider, Path descriptorPath, String pythonPreference, Path outputDirectory,
            ConfigFileChooser fileChooser, DirectoryChooser directoryChooser) {
        super(null);
        this.provider = provider;
        this.descriptorPath = descriptorPath;
        this.pythonPreference = pythonPreference;
        this.outputDirectory = outputDirectory;
        this.fileChooser = fileChooser;
        this.directoryChooser = directoryChooser;
        addMenuBar();
        addStatusLine();
    }

    @Override
    protected void configureShell(org.eclipse.swt.widgets.Shell shell) {
        super.configureShell(shell);
        shell.setText("HADES-Wizard — " + provider.display().name());
        shell.setMinimumSize(900, 520);
        var images = new ArrayList<Image>();
        for (String name : new String[] { "wizard-16.png", "wizard-32.png", "wizard-48.png" }) {
            var resource = BackendWindow.class.getResource("/icons/" + name);
            if (resource != null) {
                images.add(ImageDescriptor.createFromURL(resource).createImage());
            }
        }
        shell.setImages(images.toArray(Image[]::new));
        shell.addDisposeListener(event -> images.forEach(Image::dispose));
    }

    @Override
    protected Point getInitialSize() {
        return new Point(1180, 760);
    }

    @Override
    protected Control createContents(Composite parent) {
        var sash = new SashForm(parent, SWT.HORIZONTAL);
        modulePreview = new ModulePortPreview(sash, SWT.NONE);
        var composite = new Composite(sash, SWT.NONE);
        composite.setLayout(new GridLayout(1, false));
        new Label(composite, SWT.NONE).setText(provider.display().name());
        var description = new Label(composite, SWT.WRAP);
        description.setText(provider.display().description() + "\n" + provider.display().help());
        description.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(composite, SWT.NONE).setText("Configuration &summary:");
        summary = new Text(composite, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        sash.setWeights(36, 64);
        updateSummary();
        return sash;
    }

    @Override
    protected MenuManager createMenuManager() {
        var file = new MenuManager("&File");
        file.add(new Action("&New Configuration...", SWT.MOD1 + 'n') {
            @Override
            public void run() {
                newConfiguration();
            }
        });
        file.add(new Action("&Open...", SWT.MOD1 + 'o') {
            @Override
            public void run() {
                chooseOpenConfiguration();
            }
        });
        file.add(new Action("Edit &Configuration...", SWT.MOD1 + 'e') {
            @Override
            public void run() {
                editConfiguration();
            }
        });
        file.add(new Separator());
        file.add(new Action("&Save", SWT.MOD1 + 's') {
            @Override
            public void run() {
                saveConfiguration(false);
            }
        });
        file.add(new Action("Save &As...", SWT.MOD1 + SWT.SHIFT + 's') {
            @Override
            public void run() {
                saveConfiguration(true);
            }
        });
        file.add(new Separator());
        file.add(new Action("E&xit") {
            @Override
            public void run() {
                close();
            }
        });
        return file;
    }

    public void newConfiguration() {
        if (!confirmDiscard()) {
            return;
        }
        openWizard(null, false);
    }

    public void editConfiguration() {
        if (configuration == null) {
            newConfiguration();
            return;
        }
        openWizard(configuration, true);
    }

    private void openWizard(JsonObject initial, boolean loaded) {
        var wizard = new BackendWizard(provider, descriptorPath, pythonPreference, initial, loaded, outputDirectory,
                directoryChooser);
        activeWizard = wizard;
        int result = new WizardDialog(getShell(), wizard).open();
        if (result == WizardDialog.OK && wizard.generatedResult() != null && wizard.generatedConfig() != null) {
            configuration = wizard.generatedConfig();
            module = wizard.controller().module();
            configurationValid = BackendController.isValid(wizard.generatedResult());
            configurationRevision++;
            if (!loaded) {
                currentFile = null;
            }
            dirty = true;
            updateSummary();
            updateTitle();
        }
    }

    public void chooseOpenConfiguration() {
        if (!confirmDiscard()) {
            return;
        }
        Path selected = fileChooser.open(getShell());
        if (selected != null) {
            openConfiguration(selected);
        }
    }

    public void openConfiguration(Path path) {
        if (busy) {
            setStatus("A backend operation is already running");
            return;
        }
        try {
            if (Files.size(path) > MAX_CONFIG_BYTES) {
                throw new BackendException("E_CONFIG_TOO_LARGE", "Configuration exceeds the 2 MiB read limit");
            }
            String text = Files.readString(path);
            runBackend("Open Configuration", backend -> backend.analyzeText(text), result -> {
                if (!result.has("config") || result.get("config").isJsonNull()) {
                    showBackendDiagnostics("Open Configuration", result);
                    return;
                }
                configuration = result.getAsJsonObject("config").deepCopy();
                module = result.has("module") && !result.get("module").isJsonNull()
                        ? result.getAsJsonObject("module").deepCopy() : null;
                configurationValid = BackendController.isValid(result);
                configurationRevision++;
                currentFile = path;
                dirty = false;
                updateSummary();
                updateTitle();
                if (!configurationValid) {
                    showBackendDiagnostics("Configuration opened for repair", result);
                }
            });
        } catch (IOException | BackendException e) {
            showError("Open Configuration", "Could not open configuration", e);
        }
    }

    private void saveConfiguration(boolean alwaysChoose) {
        if (busy) {
            setStatus("A backend operation is already running");
            return;
        }
        if (configuration == null) {
            MessageDialog.openInformation(getShell(), "Save Configuration", "Complete a configuration before saving.");
            return;
        }
        Path destination = currentFile;
        boolean force = currentFile != null;
        if (alwaysChoose || destination == null) {
            destination = fileChooser.save(getShell(), currentFile);
            if (destination == null) {
                return;
            }
            force = Files.exists(destination) && MessageDialog.openConfirm(getShell(), "Overwrite Configuration",
                    "Replace " + destination + "?");
            if (Files.exists(destination) && !force) {
                return;
            }
        }
        JsonObject snapshot = configuration.deepCopy();
        Path selected = destination;
        boolean replace = force;
        runBackend("Save Configuration", backend -> backend.save(snapshot, selected, replace), result -> {
            if (!result.has("saved") || !result.get("saved").getAsBoolean()) {
                showBackendDiagnostics("Configuration was not saved", result);
                return;
            }
            configuration = result.getAsJsonObject("config").deepCopy();
            currentFile = selected;
            dirty = false;
            updateSummary();
            updateTitle();
            setStatus("Saved " + selected);
        });
    }

    private void runBackend(String title, BackendCall call, BackendResult success) {
        long revision = ++operationRevision;
        busy = true;
        setStatus(title + "...");
        updateModulePreview();
        var display = getShell().getDisplay();
        executor.execute(() -> {
            try (BackendService backend = BackendService.launch(provider, descriptorPath, pythonPreference,
                    ProviderJson.gson(), Duration.ofSeconds(30))) {
                activeBackend = backend;
                JsonObject result = call.run(backend);
                display.asyncExec(() -> {
                    if (revision == operationRevision && !getShell().isDisposed()) {
                        try {
                            success.accept(result);
                        } catch (BackendException e) {
                            showError(title, "Backend operation failed", e);
                        } finally {
                            busy = false;
                            activeBackend = null;
                            setStatus("Ready");
                            updateModulePreview();
                        }
                    }
                });
            } catch (BackendException e) {
                display.asyncExec(() -> {
                    if (revision == operationRevision && !getShell().isDisposed()) {
                        busy = false;
                        activeBackend = null;
                        setStatus("Ready");
                        updateModulePreview();
                        showError(title, "Backend operation failed", e);
                    }
                });
            }
        });
    }

    @FunctionalInterface
    private interface BackendCall {
        JsonObject run(BackendService backend) throws BackendException;
    }

    @FunctionalInterface
    private interface BackendResult {
        void accept(JsonObject result) throws BackendException;
    }

    private void updateSummary() {
        if (summary == null || summary.isDisposed()) {
            return;
        }
        if (configuration == null) {
            summary.setText("No configuration. Choose File > New Configuration to begin.");
        } else {
            var text = new StringBuilder(provider.display().name()).append('\n').append(provider.vlnv()).append("\n\n");
            for (String key : new String[] { "target", "module_name", "ip_name", "port_count", "clock_mode",
                    "configuration_mode", "port_clock_hz", "read_latency" }) {
                text.append(key).append(" = ").append(BackendController.text(configuration, key)).append('\n');
            }
            summary.setText(text.toString());
        }
        updateModulePreview();
    }

    private void updateModulePreview() {
        if (modulePreview == null || modulePreview.isDisposed()) {
            return;
        }
        modulePreview.setModel(ModulePreviewJson.fromJson(module), configurationRevision);
        modulePreview.setStatus(moduleStatus());
    }

    private String moduleStatus() {
        if (configuration == null) {
            return "No configuration. Choose File > New Configuration to begin.";
        }
        if (busy) {
            return "Backend operation in progress; the current interface is retained.";
        }
        if (module == null) {
            return "Module metadata is unavailable for this configuration.";
        }
        int groups = module.has("groups") && module.get("groups").isJsonArray()
                ? module.getAsJsonArray("groups").size() : 0;
        if (!configurationValid) {
            return "Configuration has errors; the decoded interface is informational only.";
        }
        return "Current configuration; " + groups + " signal groups.";
    }

    private void updateTitle() {
        if (getShell() != null && !getShell().isDisposed()) {
            getShell().setText("HADES-Wizard" + (currentFile == null ? "" : " - " + currentFile.getFileName())
                    + (dirty ? " *" : ""));
        }
    }

    private boolean confirmDiscard() {
        return !dirty || MessageDialog.openQuestion(getShell(), "Unsaved Configuration",
                "Discard the unsaved configuration?");
    }

    @Override
    public boolean close() {
        if (!confirmDiscard()) {
            return false;
        }
        operationRevision++;
        if (activeBackend != null) {
            activeBackend.close();
        }
        executor.shutdownNow();
        return super.close();
    }

    private void showBackendDiagnostics(String title, JsonObject result) {
        var diagnostics = BackendController.parseDiagnostics(result.getAsJsonArray("diagnostics"));
        StringBuilder message = new StringBuilder();
        for (var diagnostic : diagnostics) {
            message.append(diagnostic).append('\n');
        }
        MessageDialog.openError(getShell(), title, message.length() == 0 ? "Backend rejected the request." : message.toString());
    }

    private void showError(String title, String message, Exception e) {
        MessageDialog.openError(getShell(), title, message + ":\n" + e.getMessage());
    }
}
