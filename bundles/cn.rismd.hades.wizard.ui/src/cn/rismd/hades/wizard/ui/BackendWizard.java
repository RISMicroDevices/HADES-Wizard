package cn.rismd.hades.wizard.ui;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.fieldassist.ControlDecoration;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import cn.rismd.hades.wizard.core.backend.BackendException;
import cn.rismd.hades.wizard.core.backend.Diagnostic;
import cn.rismd.hades.wizard.core.provider.PageDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.modulepreview.ModulePortPreview;

public final class BackendWizard extends Wizard {
    private final BackendController controller;
    private final DirectoryChooser directoryChooser;
    private final List<BackendPage> pages = new ArrayList<>();
    private final List<ModulePortPreview> modulePreviews = new ArrayList<>();
    private final Map<Control, ControlDecoration> decorations = new HashMap<>();
    private final Map<Control, String> decorationPaths = new HashMap<>();
    private boolean updating;
    private JsonObject generatedResult;
    private JsonObject generatedConfig;

    public BackendWizard(ProviderDescriptor provider, Path descriptorPath, String pythonPreference,
            JsonObject initialConfig, boolean loaded, Path initialOutput, DirectoryChooser directoryChooser) {
        this.directoryChooser = directoryChooser;
        setWindowTitle(provider.display().name());
        setNeedsProgressMonitor(true);
        setHelpAvailable(false);
        controller = new BackendController(provider, descriptorPath, pythonPreference,
                org.eclipse.swt.widgets.Display.getCurrent(), initialConfig, loaded, this::stateChanged);
        controller.output(initialOutput);
    }

    public BackendController controller() {
        return controller;
    }

    public ModulePortPreview modulePreview() {
        if (getContainer() != null && getContainer().getCurrentPage() instanceof BackendPage page) {
            return page.modulePreview;
        }
        return modulePreviews.isEmpty() ? null : modulePreviews.get(0);
    }

    public AnalysisCanvas analysisCanvas(AnalysisCanvas.View view) {
        for (BackendPage page : pages) {
            if (page instanceof ReviewPage review) {
                return review.analysisCanvases.get(view);
            }
        }
        return null;
    }

    public JsonObject generatedResult() {
        return generatedResult;
    }

    public JsonObject generatedConfig() {
        return generatedConfig;
    }

    @Override
    public void addPages() {
        for (PageDescriptor descriptor : controller.provider().pages()) {
            BackendPage page = switch (descriptor.kind()) {
                case "integration" -> new IntegrationPage(descriptor);
                case "ports" -> new PortsPage(descriptor);
                case "clock" -> new ClockPage(descriptor);
                case "maintenance" -> new MaintenancePage(descriptor);
                case "review" -> new ReviewPage(descriptor);
                default -> throw new IllegalArgumentException("Unknown backend page kind " + descriptor.kind());
            };
            pages.add(page);
            addPage(page);
        }
        controller.start();
    }

    @Override
    public boolean canFinish() {
        return super.canFinish() && controller.valid() && !controller.loading() && controller.output() != null
                && !controller.output().toString().isBlank();
    }

    @Override
    public boolean performFinish() {
        if (!canFinish()) {
            return false;
        }
        Path destination = controller.output();
        boolean force = controller.force();
        generatedResult = null;
        IRunnableWithProgress operation = monitor -> {
            monitor.beginTask("Revalidate and generate HADES-SRAM artifacts", 2);
            try {
                JsonObject result = controller.generateSnapshot(destination, force);
                monitor.worked(1);
                if (monitor.isCanceled()) {
                    controller.close();
                    return;
                }
                generatedResult = result;
                monitor.worked(1);
            } catch (BackendException e) {
                throw new InvocationTargetException(e);
            }
        };
        try {
            getContainer().run(true, true, operation);
        } catch (InvocationTargetException e) {
            pages.get(4).setErrorMessage("Backend generation failed: " + e.getTargetException().getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            controller.close();
            return false;
        }
        if (generatedResult == null) {
            pages.get(4).setErrorMessage("The current revision is invalid; generation was not attempted.");
            return false;
        }
        boolean success = generatedResult.has("outcome") && "generated".equals(BackendController.text(generatedResult, "outcome"))
                && generatedResult.has("status") && generatedResult.get("status").getAsInt() == 0;
        if (!success) {
            pages.get(4).setErrorMessage("Generation did not report generated/status 0.");
            return false;
        }
        generatedConfig = controller.config();
        int artifactCount = generatedFileCount(destination);
        String message = artifactCount < 0
                ? "Generation completed successfully.\n\nOutput directory:\n" + destination
                : "Generated " + artifactCount + " managed files successfully.\n\nOutput directory:\n" + destination;
        message += controller.fullCopy()
                ? "\n\nThe complete integration source payload was copied."
                : "\n\nOnly configuration-specific artifacts were generated; maintained sources were not copied.";
        MessageDialog.openInformation(getShell(), "Generation Complete", message);
        return true;
    }

    private static int generatedFileCount(Path destination) {
        try {
            var manifest = com.google.gson.JsonParser.parseString(
                    java.nio.file.Files.readString(destination.resolve("manifest.json"))).getAsJsonObject();
            return manifest.getAsJsonArray("files").size() + 1;
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }

    @Override
    public boolean performCancel() {
        controller.close();
        return true;
    }

    @Override
    public void dispose() {
        controller.close();
        super.dispose();
    }

    private void stateChanged(BackendController ignored) {
        refreshModulePreviews();
        updating = true;
        try {
            for (BackendPage page : pages) {
                page.refreshPage();
            }
            updateDiagnostics();
        } finally {
            updating = false;
        }
        getContainer().updateButtons();
    }

    private void refreshModulePreviews() {
        JsonObject module = controller.module();
        var model = ModulePreviewJson.fromJson(module);
        for (ModulePortPreview preview : modulePreviews) {
            if (!preview.isDisposed()) {
                preview.setModel(model, controller.revision());
                preview.setStatus(moduleStatus(module));
            }
        }
    }

    private String moduleStatus(JsonObject module) {
        if (controller.loading()) {
            return "Updating module interface...";
        }
        if (controller.backendError() != null) {
            return "Backend unavailable: " + controller.backendError();
        }
        if (module == null) {
            return "No decodable module interface. Repair the configuration.";
        }
        int groups = module.has("groups") && module.get("groups").isJsonArray()
                ? module.getAsJsonArray("groups").size() : 0;
        if (!controller.valid()) {
            return "Configuration has errors; the decoded interface is informational only.";
        }
        return "Current revision " + controller.revision() + "; " + groups + " signal groups.";
    }

    private void updateDiagnostics() {
        for (ControlDecoration decoration : decorations.values()) {
            decoration.hide();
        }
        for (Diagnostic diagnostic : controller.diagnostics()) {
            Control control = findDiagnosticControl(diagnostic.path());
            if (control != null) {
                ControlDecoration decoration = decorations.get(control);
                if (decoration != null) {
                    decoration.setDescriptionText(diagnostic.message());
                    decoration.show();
                }
            }
        }
        for (BackendPage page : pages) {
            page.refreshDiagnostics();
        }
    }

    private Control findDiagnosticControl(String path) {
        for (var entry : decorationPaths.entrySet()) {
            if (entry.getValue().equals(path) || path.endsWith("." + entry.getValue())
                    || entry.getValue().endsWith("." + path)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void navigate(String path) {
        int pageIndex = pageForPath(path);
        getContainer().showPage(pages.get(pageIndex));
        pages.get(pageIndex).focusPath(path);
        updateDiagnostics();
    }

    private static int pageForPath(String path) {
        if (path.startsWith("ports")) {
            return 1;
        }
        if (path.contains("clock") || path.contains("latency") || path.contains("period")) {
            return 2;
        }
        if (path.contains("selftest") || path.contains("refresh") || path.contains("zq_")) {
            return 3;
        }
        if (path.contains("output")) {
            return 4;
        }
        return 0;
    }

    private void register(String path, Control control) {
        control.setData("hades.path", path);
        decorationPaths.put(control, path);
        ControlDecoration decoration = new ControlDecoration(control, SWT.TOP | SWT.LEFT);
        decoration.setShowHover(true);
        decoration.hide();
        decorations.put(control, decoration);
    }

    private static JsonObject config(BackendController controller) {
        JsonObject config = controller.config();
        return config == null ? new JsonObject() : config;
    }

    private static JsonArray ports(BackendController controller) {
        JsonObject config = config(controller);
        return config.has("ports") && config.get("ports").isJsonArray() ? config.getAsJsonArray("ports")
                : new JsonArray();
    }

    private static String value(JsonObject object, String key) {
        return BackendController.text(object, key);
    }

    private static String nested(JsonObject root, String group, String key) {
        if (root == null || !root.has(group) || root.get(group).isJsonNull()) {
            return "—";
        }
        return BackendController.text(root.getAsJsonObject(group), key);
    }

    private static void readonly(Composite parent, String label, String id, Map<String, String> values) {
        Label caption = new Label(parent, SWT.NONE);
        caption.setText(label);
        Text value = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
        value.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        value.setText(values.getOrDefault(id, "—"));
        value.setData("hades.derived", id);
    }

    private static void updateDerivedText(Composite parent, Map<String, String> values) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Text text && text.getData("hades.derived") != null) {
                setText(text, values.getOrDefault(text.getData("hades.derived").toString(), "—"));
            } else if (child instanceof Composite composite) {
                updateDerivedText(composite, values);
            }
        }
    }

    private abstract class BackendPage extends WizardPage {
        protected final PageDescriptor descriptor;
        protected ScrolledComposite scrolled;
        protected Composite body;
        private ModulePortPreview modulePreview;
        private org.eclipse.swt.widgets.List diagnosticList;

        BackendPage(PageDescriptor descriptor) {
            super(descriptor.id());
            this.descriptor = descriptor;
            setTitle(descriptor.title());
            setDescription(descriptor.description());
            setPageComplete(true);
        }

        @Override
        public final void createControl(Composite parent) {
            var sash = new org.eclipse.swt.custom.SashForm(parent, SWT.HORIZONTAL);
            modulePreview = new ModulePortPreview(sash, SWT.NONE);
            modulePreviews.add(modulePreview);
            scrolled = new ScrolledComposite(sash, SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
            scrolled.setExpandHorizontal(true);
            scrolled.setExpandVertical(true);
            body = new Composite(scrolled, SWT.NONE);
            body.setLayout(new GridLayout(2, false));
            body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            createBody(body);
            Label diagnosticsLabel = new Label(body, SWT.NONE);
            diagnosticsLabel.setText("&Diagnostics (select to navigate):");
            diagnosticsLabel.setLayoutData(span(2));
            diagnosticList = new org.eclipse.swt.widgets.List(body, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
            GridData diagnosticsData = span(2);
            diagnosticsData.heightHint = 68;
            diagnosticsData.grabExcessHorizontalSpace = true;
            diagnosticList.setLayoutData(diagnosticsData);
            diagnosticList.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> {
                int index = diagnosticList.getSelectionIndex();
                if (index >= 0 && index < controller.diagnostics().size()) {
                    navigate(controller.diagnostics().get(index).path());
                }
            }));
            scrolled.setContent(body);
            scrolled.setMinSize(body.computeSize(SWT.DEFAULT, SWT.DEFAULT));
            sash.setWeights(35, 65);
            setControl(sash);
            refreshPage();
            refreshModulePreviews();
        }

        abstract void createBody(Composite parent);

        void refreshPage() {
            if (body != null && !body.isDisposed()) {
                boolean previous = updating;
                updating = true;
                try {
                    refreshValues();
                    body.layout(true, true);
                    scrolled.setMinSize(body.computeSize(SWT.DEFAULT, SWT.DEFAULT));
                } finally {
                    updating = previous;
                }
            }
        }

        abstract void refreshValues();

        void refreshDiagnostics() {
            if (diagnosticList == null || diagnosticList.isDisposed()) {
                return;
            }
            diagnosticList.removeAll();
            for (Diagnostic diagnostic : controller.diagnostics()) {
                diagnosticList.add(diagnostic.toString());
            }
            Diagnostic firstError = controller.diagnostics().stream()
                    .filter(diagnostic -> diagnostic.severity().equalsIgnoreCase("error")).findFirst().orElse(null);
            if (controller.backendError() != null) {
                setErrorMessage("Backend unavailable: " + controller.backendError() + ". Edits are preserved; restart on Review.");
            } else if (firstError != null) {
                setErrorMessage(firstError.code() + " " + firstError.path() + ": " + firstError.message());
            } else {
                setErrorMessage(null);
                Diagnostic warning = controller.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.severity().equalsIgnoreCase("warning")).findFirst().orElse(null);
                setMessage(warning == null ? descriptor.description() : "Warning: " + warning.message(), WARNING);
            }
        }

        void focusPath(String path) {
            if (path.startsWith("ports")) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("ports\\[(\\d+)\\]").matcher(path);
                if (matcher.find()) {
                    controller.selectPort(Integer.parseInt(matcher.group(1)));
                }
            }
            for (var entry : decorationPaths.entrySet()) {
                if (path.equals(entry.getValue()) && !entry.getKey().isDisposed()) {
                    entry.getKey().setFocus();
                    return;
                }
            }
        }

        GridData span(int columns) {
            GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
            data.horizontalSpan = columns;
            return data;
        }
    }

    private Text textField(Composite parent, String label, String path, java.util.function.Consumer<String> edit,
            boolean numeric) {
        Label caption = new Label(parent, SWT.NONE);
        caption.setText(label);
        Text text = new Text(parent, SWT.BORDER | SWT.SINGLE);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.addModifyListener(event -> {
            if (!updating) {
                edit.accept(text.getText());
            }
        });
        register(path, text);
        return text;
    }

    private Combo comboField(Composite parent, String label, String path, String[] choices,
            java.util.function.Consumer<String> edit) {
        Label caption = new Label(parent, SWT.NONE);
        caption.setText(label);
        Combo combo = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
        combo.setItems(choices);
        combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        combo.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> {
            if (!updating && combo.getSelectionIndex() >= 0) {
                edit.accept(combo.getText());
            }
        }));
        register(path, combo);
        return combo;
    }

    private Button checkField(Composite parent, String label, String path, java.util.function.Consumer<Boolean> edit) {
        new Label(parent, SWT.NONE).setText("");
        Button button = new Button(parent, SWT.CHECK);
        button.setText(label);
        button.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> {
            if (!updating) {
                edit.accept(button.getSelection());
            }
        }));
        register(path, button);
        return button;
    }

    private static void setText(Text text, String value) {
        if (!text.getText().equals(value)) {
            text.setText(value);
        }
    }

    private static void select(Combo combo, String value) {
        int index = combo.indexOf(value);
        if (index >= 0 && combo.getSelectionIndex() != index) {
            combo.select(index);
        }
    }

    private static Table table(Composite parent, String[] columns, int height) {
        Table table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        for (String column : columns) {
            new TableColumn(table, SWT.NONE).setText(column);
        }
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.horizontalSpan = 2;
        data.heightHint = height;
        table.setLayoutData(data);
        return table;
    }

    private static void row(Table table, String... values) {
        TableItem item = new TableItem(table, SWT.NONE);
        item.setText(values);
    }

    private static void pack(Table table) {
        for (TableColumn column : table.getColumns()) {
            column.pack();
            column.setWidth(Math.max(75, column.getWidth()));
        }
    }

    private final class IntegrationPage extends BackendPage {
        private Combo target;
        private Text module;
        private Text ip;
        private Combo configuration;
        private Text count;
        private Text addressWidth;

        IntegrationPage(PageDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        void createBody(Composite parent) {
            target = comboField(parent, "&Target:", "target", new String[] { "rtl", "ip" },
                    value -> controller.editGlobal("target", new JsonPrimitive(value)));
            module = textField(parent, "&Module name:", "module_name",
                    value -> controller.editGlobal("module_name", new JsonPrimitive(value)), false);
            ip = textField(parent, "&IP name:", "ip_name",
                    value -> controller.editGlobal("ip_name", new JsonPrimitive(value)), false);
            configuration = comboField(parent, "&Configuration mode:", "configuration_mode", new String[] { "CSR", "STATIC" },
                    value -> controller.editGlobal("configuration_mode", new JsonPrimitive(value)));
            count = textField(parent, "Physical &ports:", "port_count", value -> {
                try {
                    int next = Integer.parseInt(value);
                    int previous = config(controller).get("port_count").getAsInt();
                    if (next < previous && !MessageDialog.openConfirm(getShell(), "Remove configured ports",
                            "Shrink the port count and discard configured tail ports?")) {
                        stateChanged(controller);
                        return;
                    }
                    controller.resizePorts(next);
                } catch (NumberFormatException e) {
                    controller.editGlobal("port_count", new JsonPrimitive(value));
                }
            }, true);
            addressWidth = textField(parent, "&Address width (bits):", "address_width",
                    value -> controller.editGlobal("address_width", BackendController.jsonValue(value, true)), true);
            Label help = new Label(parent, SWT.WRAP);
            help.setText("RTL emits standalone sources. IP emits configure Tcl/wrapper for an already packaged Vivado catalog IP. CSR uses host-initialized registers; STATIC uses hard-wired parameters.");
            help.setLayoutData(span(2));
        }

        @Override
        void refreshValues() {
            JsonObject config = config(controller);
            select(target, value(config, "target"));
            setText(module, value(config, "module_name"));
            setText(ip, value(config, "ip_name"));
            ip.setEnabled("ip".equals(value(config, "target")));
            select(configuration, value(config, "configuration_mode"));
            setText(count, value(config, "port_count"));
            setText(addressWidth, value(config, "address_width"));
        }
    }

    private final class PortsPage extends BackendPage {
        private Combo mode;
        private Combo selected;
        private Table table;
        private Text width;
        private Text depth;
        private Text base;
        private Button byteEnable;
        private Button enabled;
        private Text size;
        private Text capacity;
        private Text tail;
        private Text padding;

        PortsPage(PageDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        void createBody(Composite parent) {
            mode = comboField(parent, "Address &mode:", "address_mode", new String[] { "Automatic", "Manual" }, value -> {
                boolean automatic = value.equals("Automatic");
                if (automatic && !MessageDialog.openConfirm(getShell(), "Pack automatic addresses",
                        "Use current depths, normalize raw tails, and pack all backend bases?")) {
                    stateChanged(controller);
                    return;
                }
                controller.addressingMode(automatic);
            });
            Label selectedLabel = new Label(parent, SWT.NONE);
            selectedLabel.setText("Selected &port:");
            selected = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
            selected.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            selected.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> {
                if (!updating) {
                    controller.selectPort(selected.getSelectionIndex());
                }
            }));
            table = table(parent, new String[] { "Port", "Width", "Depth", "Base beats", "BE", "Enabled", "Size lanes",
                    "Capacity words", "Tail lanes", "Window padding" }, 140);
            table.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> {
                if (!updating && table.getSelectionIndex() >= 0) {
                    controller.selectPort(table.getSelectionIndex());
                }
            }));
            int index = controller.selectedPort();
            width = textField(parent, "&Width (bits):", "ports[" + index + "].width",
                    value -> controller.editPort(controller.selectedPort(), "width", BackendController.jsonValue(value, true)), true);
            depth = textField(parent, "&Depth (frontend words):", "ports[" + index + "].depth",
                    value -> controller.editPort(controller.selectedPort(), "depth", BackendController.jsonValue(value, true)), true);
            base = textField(parent, "&Base (64-byte beats):", "ports[" + index + "].base_beats",
                    value -> controller.editPort(controller.selectedPort(), "base_beats", BackendController.jsonValue(value, true)), true);
            byteEnable = checkField(parent, "&Byte enable", "ports[" + index + "].byte_enable",
                    value -> controller.editPort(controller.selectedPort(), "byte_enable", new JsonPrimitive(value)));
            enabled = checkField(parent, "&Enabled", "ports[" + index + "].enabled",
                    value -> controller.editPort(controller.selectedPort(), "enabled", new JsonPrimitive(value)));
            size = derivedText(parent, "Size (32-bit lanes):");
            capacity = derivedText(parent, "Capacity (frontend words):");
            tail = derivedText(parent, "Raw tail lanes:");
            padding = derivedText(parent, "Backend window padding lanes:");
            Label help = new Label(parent, SWT.WRAP);
            help.setText("Automatic edits allocate current depths. Manual base edits only analyze; depth/width edits normalize only the selected port.");
            help.setLayoutData(span(2));
        }

        private Text derivedText(Composite parent, String label) {
            new Label(parent, SWT.NONE).setText(label);
            Text text = new Text(parent, SWT.BORDER | SWT.READ_ONLY);
            text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            return text;
        }

        @Override
        void refreshValues() {
            select(mode, controller.automaticAddressing() ? "Automatic" : "Manual");
            JsonArray ports = ports(controller);
            selected.removeAll();
            for (int i = 0; i < ports.size(); i++) {
                selected.add("Port " + i);
            }
            if (controller.selectedPort() >= 0 && controller.selectedPort() < ports.size()) {
                selected.select(controller.selectedPort());
            }
            table.removeAll();
            JsonArray depths = controller.depths();
            JsonArray analysisPorts = controller.analysis() != null && controller.analysis().has("ports")
                    ? controller.analysis().getAsJsonArray("ports") : new JsonArray();
            for (int i = 0; i < ports.size(); i++) {
                JsonObject port = ports.get(i).getAsJsonObject();
                JsonObject analysis = i < analysisPorts.size() ? analysisPorts.get(i).getAsJsonObject() : new JsonObject();
                String depthValue = i < depths.size() && !depths.get(i).isJsonNull() ? depths.get(i).getAsString() : "—";
                row(table, Integer.toString(i), value(port, "width"), depthValue, value(port, "base_beats"),
                        value(port, "byte_enable"), value(port, "enabled"), value(port, "size_lanes"),
                        value(analysis, "capacity_words"), value(analysis, "size_tail_lanes"),
                        value(analysis, "backend_window_padding_lanes"));
            }
            pack(table);
            if (controller.selectedPort() >= 0 && controller.selectedPort() < ports.size()) {
                table.select(controller.selectedPort());
                JsonObject port = ports.get(controller.selectedPort()).getAsJsonObject();
                setText(width, value(port, "width"));
                int index = controller.selectedPort();
                for (var entry : Map.of("width", width, "depth", depth, "base_beats", base, "byte_enable", byteEnable,
                        "enabled", enabled).entrySet()) {
                    String path = "ports[" + index + "]." + entry.getKey();
                    decorationPaths.put(entry.getValue(), path);
                    entry.getValue().setData("hades.path", path);
                }
                setText(depth, index < depths.size() && !depths.get(index).isJsonNull() ? depths.get(index).getAsString() : "—");
                setText(base, value(port, "base_beats"));
                base.setEditable(!controller.automaticAddressing());
                byteEnable.setSelection(port.has("byte_enable") && port.get("byte_enable").getAsBoolean());
                enabled.setSelection(port.has("enabled") && port.get("enabled").getAsBoolean());
                setText(size, value(port, "size_lanes"));
                JsonObject analysis = index < analysisPorts.size() ? analysisPorts.get(index).getAsJsonObject() : new JsonObject();
                setText(capacity, value(analysis, "capacity_words"));
                setText(tail, value(analysis, "size_tail_lanes"));
                setText(padding, value(analysis, "backend_window_padding_lanes"));
            }
        }
    }

    private final class ClockPage extends BackendPage {
        private Combo mode;
        private Text frequency;
        private Combo latency;
        private Button automatic;
        private Text target;
        private Text minimum;
        private final Map<String, String> derived = new HashMap<>();

        ClockPage(PageDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        void createBody(Composite parent) {
            mode = comboField(parent, "Clock &mode:", "clock_mode", new String[] { "SYNC", "ASYNC" },
                    value -> controller.editGlobal("clock_mode", new JsonPrimitive(value)));
            frequency = textField(parent, "Port clock (&Hz):", "port_clock_hz",
                    value -> controller.frequency(BackendController.jsonValue(value, true)), true);
            latency = comboField(parent, "Read &latency (cycles):", "read_latency", new String[] { "1", "2" },
                    value -> controller.editGlobal("read_latency", new JsonPrimitive(Integer.parseInt(value))));
            var advanced = new org.eclipse.swt.widgets.Group(parent, SWT.NONE);
            advanced.setText("Advanced period control");
            advanced.setLayout(new GridLayout(2, false));
            advanced.setLayoutData(span(2));
            automatic = checkField(advanced, "&Automatically derive periods", "automatic_periods", controller::timingMode);
            target = textField(advanced, "&Target period (ui_clk cycles):", "target_period_cycles",
                    value -> controller.timingValue("target_period_cycles", BackendController.jsonValue(value, true)), true);
            minimum = textField(advanced, "M&inimum period (ui_clk cycles):", "min_period_cycles",
                    value -> controller.timingValue("min_period_cycles", BackendController.jsonValue(value, true)), true);
            new Label(advanced, SWT.NONE).setText("");
            Button derive = new Button(advanced, SWT.PUSH);
            derive.setText("&Derive / reset periods");
            derive.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> controller.derivePeriods()));
            Label analytical = new Label(parent, SWT.WRAP);
            analytical.setText("Analytical only: ui_clk is 300 MHz. Requested frequency, exact ratio, and cycle-model rate can differ. No implementation timing signoff.");
            analytical.setLayoutData(span(2));
            derived.clear();
            derived.put("ui", "300000000 Hz (300 MHz)");
            for (var entry : new String[][] { { "requested", "Requested frequency / readable kHz" },
                    { "period", "Requested port period (ns)" }, { "ratio", "Exact frequency ratio" },
                    { "cycleRate", "Cycle-model port rate (Hz)" }, { "latency", "Read latency cycles / ns" },
                    { "budget", "Budget used / target cycles" }, { "headroom", "Headroom cycles" } }) {
                readonly(parent, entry[1], entry[0], derived);
            }
        }

        @Override
        void refreshValues() {
            JsonObject config = config(controller);
            select(mode, value(config, "clock_mode"));
            setText(frequency, value(config, "port_clock_hz"));
            select(latency, value(config, "read_latency"));
            automatic.setSelection(controller.automaticTiming());
            setText(target, value(config, "target_period_cycles"));
            setText(minimum, value(config, "min_period_cycles"));
            JsonObject analysis = controller.analysis();
            derived.put("requested", nested(analysis, "clock", "port_requested_hz") + " Hz / "
                    + humanKhz(nested(analysis, "clock", "port_requested_hz")));
            derived.put("period", nested(analysis, "clock", "port_period_ns_text"));
            derived.put("ratio", nested(analysis, "clock", "frequency_ratio_exact") + " ("
                    + nested(analysis, "clock", "frequency_ratio_text") + ")");
            derived.put("cycleRate", nested(analysis, "clock", "cycle_model_port_hz_text"));
            derived.put("latency", nested(analysis, "timing", "read_latency_cycles") + " / "
                    + nested(analysis, "timing", "read_latency_ns_text") + " ns");
            derived.put("budget", nested(analysis, "timing", "budget_used_cycles") + " / "
                    + nested(analysis, "timing", "target_period_cycles"));
            derived.put("headroom", nested(analysis, "timing", "headroom_cycles") + " (strict pass: "
                    + nested(analysis, "timing", "strict_budget_passes") + ")");
            updateDerivedText(body, derived);
        }
    }

    private static String humanKhz(String hz) {
        try {
            return String.format(java.util.Locale.ROOT, "%.3f kHz", Double.parseDouble(hz) / 1000.0);
        } catch (NumberFormatException e) {
            return "—";
        }
    }

    private final class MaintenancePage extends BackendPage {
        private Button selftest;
        private Text block;
        private Text refresh;
        private Text zq;
        private final Map<String, String> derived = new HashMap<>();

        MaintenancePage(PageDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        void createBody(Composite parent) {
            selftest = checkField(parent, "Enable &self-test", "selftest_enabled",
                    value -> controller.editGlobal("selftest_enabled", new JsonPrimitive(value)));
            Label help = new Label(parent, SWT.WRAP);
            help.setText("Self-test supports only 32/64-bit widths for EVERY PHYSICAL PORT, including runtime-disabled ports. Refresh and ZQ intervals below are backend-derived.");
            help.setLayoutData(span(2));
            var advanced = new org.eclipse.swt.widgets.Group(parent, SWT.NONE);
            advanced.setText("Advanced maintenance");
            advanced.setLayout(new GridLayout(2, false));
            advanced.setLayoutData(span(2));
            block = textField(advanced, "Self-test &block (frontend words):", "selftest_block_words",
                    value -> controller.editGlobal("selftest_block_words", BackendController.jsonValue(value, true)), true);
            refresh = textField(advanced, "&Refresh period (ui_clk cycles):", "refresh_cycles",
                    value -> controller.editGlobal("refresh_cycles", BackendController.jsonValue(value, true)), true);
            zq = textField(advanced, "&ZQ calibration (port_clk periods):", "zq_periods",
                    value -> controller.editGlobal("zq_periods", BackendController.jsonValue(value, true)), true);
            readonly(advanced, "Refresh interval (µs):", "refresh", derived);
            readonly(advanced, "ZQ interval (ms):", "zq", derived);
        }

        @Override
        void refreshValues() {
            JsonObject config = config(controller);
            selftest.setSelection(config.has("selftest_enabled") && config.get("selftest_enabled").getAsBoolean());
            setText(block, value(config, "selftest_block_words"));
            setText(refresh, value(config, "refresh_cycles"));
            setText(zq, value(config, "zq_periods"));
            JsonObject analysis = controller.analysis();
            derived.put("refresh", nested(analysis, "maintenance", "refresh_interval_us"));
            derived.put("zq", nested(analysis, "maintenance", "zq_interval_ms"));
            updateDerivedText(body, derived);
        }
    }

    private final class ReviewPage extends BackendPage {
        private Text summary;
        private Table timing;
        private Table slots;
        private Table memory;
        private Table bandwidth;
        private final Map<AnalysisCanvas.View, AnalysisCanvas> analysisCanvases = new java.util.EnumMap<>(
                AnalysisCanvas.View.class);
        private Combo artifacts;
        private Text source;
        private Text output;
        private Button overwrite;
        private Button fullCopy;
        private Button restart;

        ReviewPage(PageDescriptor descriptor) {
            super(descriptor);
        }

        @Override
        void createBody(Composite parent) {
            Label summaryLabel = new Label(parent, SWT.NONE);
            summaryLabel.setText("Configuration &summary:");
            summaryLabel.setLayoutData(span(2));
            summary = new Text(parent, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            GridData summaryData = span(2);
            summaryData.heightHint = 155;
            summary.setLayoutData(summaryData);
            createAnalysisTabs(parent);
            output = textField(parent, "&Output directory:", "output", value -> {
                if (value.isBlank()) {
                    controller.output(null);
                } else {
                    controller.output(Path.of(value));
                }
            }, false);
            new Label(parent, SWT.NONE).setText("");
            Button browse = new Button(parent, SWT.PUSH);
            browse.setText("&Browse...");
            browse.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> {
                Path chosen = directoryChooser.choose(getShell(), "Select output directory", controller.output());
                if (chosen != null) {
                    controller.output(chosen);
                }
            }));
            overwrite = checkField(parent, "&Overwrite existing managed artifacts", "force", controller::force);
            fullCopy = checkField(parent, "&Copy complete integration sources", "full_copy", controller::fullCopy);
            new Label(parent, SWT.NONE).setText("");
            Button preview = new Button(parent, SWT.PUSH);
            preview.setText("Load read-only &preview");
            preview.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> controller.loadPreview()));
            Label artifactLabel = new Label(parent, SWT.NONE);
            artifactLabel.setText("&Artifact:");
            artifacts = new Combo(parent, SWT.DROP_DOWN | SWT.READ_ONLY);
            artifacts.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            artifacts.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> showArtifact()));
            source = new Text(parent, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.H_SCROLL);
            GridData sourceData = span(2);
            sourceData.heightHint = 170;
            source.setLayoutData(sourceData);
            new Label(parent, SWT.NONE).setText("");
            restart = new Button(parent, SWT.PUSH);
            restart.setText("Restart &backend (preserve edits)");
            restart.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(event -> controller.restart()));
        }

        private void createAnalysisTabs(Composite parent) {
            var tabs = new org.eclipse.swt.widgets.TabFolder(parent, SWT.NONE);
            tabs.setData("hades.reviewTabs", true);
            tabs.setLayoutData(span(2));
            GridData data = span(2);
            data.heightHint = 500;
            tabs.setLayoutData(data);
            timing = createAnalysisTab(tabs, AnalysisCanvas.View.TIMING, "Timing",
                    new String[] { "Exact-L timing", "Raw value" });
            slots = createAnalysisTab(tabs, AnalysisCanvas.View.SLOT_BUDGET, "Slot Budget",
                    new String[] { "TDMA port", "Nominal offset cycles" });
            memory = createAnalysisTab(tabs, AnalysisCanvas.View.MEMORY_MAP, "Memory Map",
                    new String[] { "Port", "Base", "End", "Size lanes", "Tail", "Window padding", "Capacity" });
            bandwidth = createAnalysisTab(tabs, AnalysisCanvas.View.BANDWIDTH, "Bandwidth",
                    new String[] { "Configured bandwidth", "Raw value" });
        }

        private Table createAnalysisTab(org.eclipse.swt.widgets.TabFolder tabs, AnalysisCanvas.View view, String label,
                String[] columns) {
            var item = new org.eclipse.swt.widgets.TabItem(tabs, SWT.NONE);
            item.setText(label);
            var sash = new org.eclipse.swt.custom.SashForm(tabs, SWT.VERTICAL);
            sash.setLayout(new GridLayout(1, false));
            AnalysisCanvas canvas;
            Control canvasControl;
            if (view == AnalysisCanvas.View.MEMORY_MAP) {
                var scroll = new ScrolledComposite(sash, SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
                scroll.setExpandHorizontal(true);
                scroll.setExpandVertical(false);
                canvas = new AnalysisCanvas(scroll, view);
                scroll.setContent(canvas);
                canvas.setSize(canvas.preferredSize());
                scroll.setMinSize(canvas.preferredSize());
                canvasControl = scroll;
            } else {
                canvas = new AnalysisCanvas(sash, view);
                canvasControl = canvas;
            }
            analysisCanvases.put(view, canvas);
            Table alternative = table(sash, columns, 100);
            alternative.setData("hades.analysisTable", view.name());
            sash.setWeights(70, 30);
            item.setControl(sash);
            if (canvasControl != canvas) {
                canvasControl.setData("hades.canvasScroll", view.name());
            }
            return alternative;
        }

        private void showArtifact() {
            JsonObject preview = controller.previewResult();
            if (preview == null || !preview.has("artifacts") || preview.get("artifacts").isJsonNull()) {
                source.setText("Preview is stale or unavailable. Load it again; no files are written.");
                return;
            }
            String name = artifacts.getText();
            JsonObject values = preview.getAsJsonObject("artifacts");
            source.setText(values.has(name) ? values.get(name).getAsString() : "");
        }

        @Override
        void refreshValues() {
            JsonObject config = config(controller);
            JsonObject analysis = controller.analysis();
            StringBuilder text = new StringBuilder();
            text.append("Target: ").append(value(config, "target").toUpperCase(java.util.Locale.ROOT))
                    .append("; module: ").append(value(config, "module_name"));
            if ("ip".equals(value(config, "target"))) {
                text.append("; IP: ").append(value(config, "ip_name"));
            }
            text.append("\nConfiguration: ").append(value(config, "configuration_mode")).append("; clock: ")
                    .append(value(config, "clock_mode")).append("; addressing: ")
                    .append(controller.automaticAddressing() ? "Automatic (packed)" : "Manual (preserved bases)");
            text.append("\nPhysical ports: ").append(value(config, "port_count")).append("; runtime enabled ports: ")
                    .append(nested(analysis, "runtime", "enabled_ports"));
            text.append("\nui_clk: ").append(nested(analysis, "clock", "ui_hz")).append(" Hz; requested port clock: ")
                    .append(nested(analysis, "clock", "port_requested_hz")).append(" Hz / ")
                    .append(nested(analysis, "clock", "port_period_ns_text")).append(" ns");
            text.append("\nExact ratio: ").append(nested(analysis, "clock", "frequency_ratio_exact"))
                    .append("; cycle-model rate: ").append(nested(analysis, "clock", "cycle_model_port_hz_text"))
                    .append(" Hz");
            text.append("\nRead latency: ").append(nested(analysis, "timing", "read_latency_cycles"))
                    .append(" frontend cycles / ").append(nested(analysis, "timing", "read_latency_ns_text")).append(" ns");
            text.append("\nBudget: ").append(nested(analysis, "timing", "budget_used_cycles")).append(" used / ")
                    .append(nested(analysis, "timing", "target_period_cycles")).append(" target cycles; headroom: ")
                    .append(nested(analysis, "timing", "headroom_cycles")).append("; strict pass: ")
                    .append(nested(analysis, "timing", "strict_budget_passes"));
            text.append("\nAnalytical only — no post-route timing, measured throughput, or board signoff.");
            setText(summary, text.toString());
            setText(output, controller.output() == null ? "" : controller.output().toString());
            overwrite.setSelection(controller.force());
            fullCopy.setSelection(controller.fullCopy());
            restart.setVisible(controller.backendError() != null);
            timing.removeAll();
            slots.removeAll();
            memory.removeAll();
            bandwidth.removeAll();
            for (var entry : analysisCanvases.entrySet()) {
                entry.getValue().setAnalysis(analysis, controller.revision());
                if (entry.getKey() == AnalysisCanvas.View.MEMORY_MAP && entry.getValue().getParent() instanceof ScrolledComposite scroll) {
                    entry.getValue().setSize(entry.getValue().preferredSize());
                    scroll.setMinSize(entry.getValue().preferredSize());
                }
            }
            if (analysis != null) {
                JsonObject timingValues = analysis.getAsJsonObject("timing");
                for (String key : new String[] { "read_latency_cycles", "read_latency_ns_text", "maintenance_cycles",
                        "first_data_cycles", "cdc_overhead_cycles", "port_slot_cycles", "margin_cycles", "budget_used_cycles",
                        "target_period_cycles", "headroom_cycles", "strict_budget_passes" }) {
                    row(timing, key, value(timingValues, key));
                }
                if (timingValues.has("nominal_slots")) {
                    for (JsonElement item : timingValues.getAsJsonArray("nominal_slots")) {
                        JsonObject slot = item.getAsJsonObject();
                        row(slots, value(slot, "port"), value(slot, "offset_cycles"));
                    }
                }
                for (JsonElement item : analysis.getAsJsonArray("ports")) {
                    JsonObject port = item.getAsJsonObject();
                    row(memory, value(port, "index"), value(port, "base_beats"), value(port, "backend_end_beats"),
                            value(port, "size_lanes"), value(port, "size_tail_lanes"),
                            value(port, "backend_window_padding_lanes"), value(port, "capacity_words"));
                }
                JsonObject bandwidthValues = analysis.getAsJsonObject("bandwidth");
                for (String key : new String[] { "useful_payload_bps", "frontend_payload_bytes_per_second",
                        "allocated_backend_bytes_per_second", "backend_theoretical_bytes_per_second",
                        "allocated_backend_fraction" }) {
                    row(bandwidth, key, value(bandwidthValues, key));
                }
            }
            pack(timing);
            pack(slots);
            pack(memory);
            pack(bandwidth);
            JsonObject preview = controller.previewResult();
            String selectedArtifact = artifacts.getText();
            artifacts.removeAll();
            if (preview != null && preview.has("artifacts") && !preview.get("artifacts").isJsonNull()) {
                for (String name : preview.getAsJsonObject("artifacts").keySet()) {
                    artifacts.add(name);
                }
                int index = artifacts.indexOf(selectedArtifact);
                artifacts.select(index >= 0 ? index : 0);
            }
            showArtifact();
        }
    }
}
