package cn.rismd.hades.wizard.ui.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.waits.DefaultCondition;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import cn.rismd.hades.wizard.core.backend.BackendService;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderJson;
import cn.rismd.hades.wizard.core.provider.ProviderLoader;
import cn.rismd.hades.wizard.modulepreview.ModulePortPreview;
import cn.rismd.hades.wizard.ui.AnalysisCanvas;
import cn.rismd.hades.wizard.ui.BackendController;
import cn.rismd.hades.wizard.ui.BackendWizard;
import cn.rismd.hades.wizard.ui.BackendWindow;
import cn.rismd.hades.wizard.ui.ConfigFileChooser;
import cn.rismd.hades.wizard.ui.DirectoryChooser;

public class SramWizardRealSWTBotTest {
    private static Path descriptorPath;
    private static ProviderDescriptor provider;
    private Display display;
    private Thread eventThread;
    private Path temporary;
    private SWTBot bot;
    private SWTBotShell wizardShell;
    private final AtomicReference<BackendWizard> wizard = new AtomicReference<>();
    private final AtomicReference<WizardDialog> dialog = new AtomicReference<>();

    @BeforeClass
    public static void loadProvider() throws Exception {
        String configured = System.getProperty("hades.sram.descriptor");
        Assume.assumeTrue(configured != null && !configured.isBlank());
        descriptorPath = Path.of(configured);
        try (var input = Files.newInputStream(descriptorPath)) {
            provider = new ProviderLoader().load(input);
        }
    }

    @Before
    public void startDisplay() throws Exception {
        temporary = Files.createTempDirectory("hades-sram-swtbot");
        Files.createDirectories(Path.of("target/screenshots"));
        var ready = new CountDownLatch(1);
        eventThread = new Thread(() -> {
            display = new Display();
            ready.countDown();
            while (!display.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
        }, "HADES-SRAM real SWTBot display");
        eventThread.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        bot = new SWTBot();
    }

    @After
    public void cleanup() throws Exception {
        if (display != null && !display.isDisposed()) {
            display.syncExec(() -> {
                if (wizard.get() != null) {
                    wizard.get().controller().close();
                }
                if (dialog.get() != null && dialog.get().getShell() != null && !dialog.get().getShell().isDisposed()) {
                    dialog.get().getShell().close();
                }
                display.dispose();
            });
        }
        eventThread.join(10_000);
    }

    @Test
    public void defaultsDepthPreviewInvalidationFinishAndArtifactParity() throws Exception {
        openWizard(null, false);
        awaitIdle();
        JsonObject defaults = wizard.get().controller().config();
        assertEquals(1, defaults.get("port_count").getAsInt());
        assertEquals(32, port(defaults, 0).get("width").getAsInt());
        assertFalse(port(defaults, 0).get("byte_enable").getAsBoolean());
        assertEquals("SYNC", defaults.get("clock_mode").getAsString());
        assertEquals("CSR", defaults.get("configuration_mode").getAsString());
        assertEquals(500000, defaults.get("port_clock_hz").getAsInt());
        assertEquals(2, defaults.get("read_latency").getAsInt());
        assertTrue(wizard.get().controller().automaticAddressing());
        assertTrue(wizard.get().controller().fullCopy());
        assertNotNull(wizard.get().controller().module());
        assertNotNull(wizard.get().modulePreview());
        capture("01-integration-default.png");

        next();
        capture("02-ports-automatic.png");
        setText("Depth (frontend words):", "17");
        assertEquals(17, analysisPort(0).get("capacity_words").getAsInt());
        next();
        capture("03-clock-automatic.png");
        next();
        capture("04-maintenance-default.png");
        next();
        bot.button("Load read-only preview").click();
        awaitIdle();
        assertNotNull(wizard.get().controller().previewResult());
        assertTrue(wizard.get().controller().previewResult().getAsJsonObject("artifacts").size() > 0);
        checkBox("Copy complete integration sources").click();
        awaitIdle();
        assertNull(wizard.get().controller().previewResult());
        bot.button("Load read-only preview").click();
        awaitIdle();
        JsonObject reduced = wizard.get().controller().previewResult().getAsJsonObject("artifacts");
        assertFalse(reduced.has("static_rtl.f"));
        assertFalse(reduced.has("logical/hades_sram_top.sv"));
        assertTrue(reduced.has("constraints/sram0_strict.xdc"));
        checkBox("Copy complete integration sources").click();
        awaitIdle();
        bot.button("Load read-only preview").click();
        awaitIdle();
        assertTrue(wizard.get().controller().previewResult().getAsJsonObject("artifacts").has("static_rtl.f"));
        selectAnalysisTab(AnalysisCanvas.View.TIMING, "Timing", "05-review-timing-memory-preview.png");
        selectAnalysisTab(AnalysisCanvas.View.SLOT_BUDGET, "Slot Budget", "11-review-slot-budget.png");
        selectAnalysisTab(AnalysisCanvas.View.MEMORY_MAP, "Memory Map", "12-review-memory-map.png");
        selectAnalysisTab(AnalysisCanvas.View.BANDWIDTH, "Bandwidth", "13-review-bandwidth.png");
        Path output = temporary.resolve("generated");
        assertFalse(Files.exists(output));

        bot.button("< Back").click();
        bot.button("< Back").click();
        bot.button("< Back").click();
        setText("Depth (frontend words):", "18");
        assertNull(wizard.get().controller().previewResult());
        assertFalse(Files.exists(output));
        goReview();
        assertAnalysisView(AnalysisCanvas.View.TIMING, "Timing", true);
        assertAnalysisView(AnalysisCanvas.View.SLOT_BUDGET, "Slot Budget", true);
        assertAnalysisView(AnalysisCanvas.View.MEMORY_MAP, "Memory Map", true);
        assertAnalysisView(AnalysisCanvas.View.BANDWIDTH, "Bandwidth", true);
        assertEquals(18, analysisPort(0).get("capacity_words").getAsInt());
        wizard.get().controller().output(output);
        awaitIdle();
        bot.button("Finish").click();
        awaitClosed();
        assertEquals("generated", wizard.get().generatedResult().get("outcome").getAsString());
        assertTrue(Files.isDirectory(output));
        try (BackendService direct = service()) {
            JsonObject reference = direct.preview(wizard.get().generatedConfig()).getAsJsonObject("artifacts");
            for (String name : reference.keySet()) {
                assertEquals(name, reference.get(name).getAsString(), Files.readString(output.resolve(name)));
            }
        }
    }

    @Test
    public void modulePreviewControlsAndLiveMetadata() throws Exception {
        openWizard(null, false);
        ModulePortPreview preview = wizard.get().modulePreview();
        display.syncExec(() -> {
            assertNotNull(preview.getModel());
            assertEquals("hades_sram_user", preview.getModuleNameLabel().getText());
            preview.setGroupExpanded("sram0", true);
            preview.setGroupExpanded("m_axi", true);
            preview.setGroupExpanded("s_axil", true);
            assertTrue(preview.isGroupExpanded("sram0"));
            assertTrue(preview.isGroupExpanded("m_axi"));
            assertTrue(preview.isGroupExpanded("s_axil"));
            preview.setGroupExpanded("m_axi", false);
            assertFalse(preview.isGroupExpanded("m_axi"));
            preview.setGroupExpanded("m_axi", true);
            var group = treeGroup(preview, "sram0");
            assertNotNull(group);
            assertTrue(group.getExpanded());
            assertEquals("SRAM Port 0", group.getText(0));
            assertEquals("sram0_wdata", group.getItem(3).getText(0));
            assertEquals("INPUT", group.getItem(3).getText(1));
            assertEquals("32", group.getItem(3).getText(2));
            assertEquals("port_clk", group.getItem(3).getText(3));
            preview.getTabs().setSelection(1);
        });
        bot.button("Zoom In").click();
        display.syncExec(() -> assertEquals("125%", preview.getZoomLabel().getText()));
        bot.button("Zoom Out").click();
        display.syncExec(() -> {
            assertEquals(1.0, preview.getZoom(), 0.0);
            preview.panBy(42, 18);
            assertEquals(42.0, preview.getPanX(), 0.0);
            assertEquals(18.0, preview.getPanY(), 0.0);
        });
        bot.button("Fit").click();
        display.syncExec(() -> {
            assertTrue(preview.getZoom() >= ModulePortPreview.MIN_ZOOM);
            assertTrue(preview.getZoom() <= ModulePortPreview.MAX_ZOOM);
        });
        bot.button("Reset").click();
        display.syncExec(() -> {
            assertEquals(1.0, preview.getZoom(), 0.0);
            assertEquals(0.0, preview.getPanX(), 0.0);
            assertEquals(0.0, preview.getPanY(), 0.0);
        });
        setText("Module name:", "preview_live");
        display.syncExec(() -> {
            assertEquals("preview_live", preview.getModuleNameLabel().getText());
            assertTrue(preview.isGroupExpanded("sram0"));
            assertTrue(preview.isGroupExpanded("m_axi"));
            assertTrue(preview.isGroupExpanded("s_axil"));
        });

        next();
        ModulePortPreview portsPreview = wizard.get().modulePreview();
        display.syncExec(() -> portsPreview.setGroupExpanded("sram0", true));
        setText("Width (bits):", "48");
        display.syncExec(() -> {
            assertTrue(portsPreview.isGroupExpanded("sram0"));
            assertPreviewSignal(portsPreview, "sram0", "sram0_wdata", "48");
            portsPreview.getTabs().setSelection(0);
        });
        capture("16-module-preview-expanded.png");
        display.syncExec(() -> {
            assertNull(portsPreview.getLastPaintError());
            assertEquals(wizard.get().controller().revision(), portsPreview.getLastPaintRevision());
        });
    }

    @Test
    public void manualBaseLegacyTailAndBothModeSwitches() throws Exception {
        JsonObject initial = allocateInitial(40, 17);
        JsonObject port = port(initial, 0);
        port.addProperty("size_lanes", port.get("size_lanes").getAsLong() + 1);
        openWizard(initial, true);
        awaitIdle();
        assertFalse(wizard.get().controller().automaticAddressing());
        assertFalse(wizard.get().controller().automaticTiming());
        next();
        long rawSize = port(wizard.get().controller().config(), 0).get("size_lanes").getAsLong();
        String rawTail = analysisPort(0).get("size_tail_lanes").getAsString();
        setText("Base (64-byte beats):", "9");
        assertEquals(9, port(wizard.get().controller().config(), 0).get("base_beats").getAsInt());
        assertEquals(rawSize, port(wizard.get().controller().config(), 0).get("size_lanes").getAsLong());
        assertEquals(rawTail, analysisPort(0).get("size_tail_lanes").getAsString());
        capture("06-ports-manual-legacy-tail.png");

        bot.comboBoxWithLabel("Address mode:").setSelection("Manual");
        awaitIdle();
        bot.comboBoxWithLabel("Address mode:").setSelection("Automatic");
        bot.shell("Pack automatic addresses").bot().button("OK").click();
        awaitIdle();
        assertTrue(wizard.get().controller().automaticAddressing());
        assertEquals(0, port(wizard.get().controller().config(), 0).get("base_beats").getAsInt());
        bot.comboBoxWithLabel("Address mode:").setSelection("Manual");
        awaitIdle();
        assertFalse(wizard.get().controller().automaticAddressing());
        assertEquals(0, port(wizard.get().controller().config(), 0).get("base_beats").getAsInt());
    }

    @Test
    public void widthCountReallocationMixedStaticSparseSelftestRepairAndN64() throws Exception {
        openWizard(null, false);
        awaitIdle();
        setText("Physical ports:", "4");
        bot.comboBoxWithLabel("Configuration mode:").setSelection("STATIC");
        awaitIdle();
        setText("Module name:", "hades_static_prefix");
        next();
        int[] widths = { 40, 1, 300, 512 };
        for (int i = 0; i < widths.length; i++) {
            bot.comboBoxWithLabel("Selected port:").setSelection(i);
            setText("Width (bits):", Integer.toString(widths[i]));
            checkBox("Byte enable").click();
            awaitIdle();
            assertTrue("Port " + i + ": " + wizard.get().controller().backendError() + " "
                    + wizard.get().controller().diagnostics(), wizard.get().controller().valid());
            assertEquals(4194304, analysisPort(i).get("capacity_words").getAsLong());
        }
        JsonArray ports = wizard.get().controller().config().getAsJsonArray("ports");
        assertEquals(4, ports.size());
        for (int i = 0; i < widths.length; i++) {
            assertEquals(widths[i], ports.get(i).getAsJsonObject().get("width").getAsInt());
            assertTrue(ports.get(i).getAsJsonObject().get("byte_enable").getAsBoolean());
        }
        assertTrue(ports.get(2).getAsJsonObject().get("base_beats").getAsLong() > 0);
        assertModuleSignal("sram2", "sram2_wdata", "in", "300");
        assertModuleSignal("sram3", "sram3_rdata", "out", "512");
        assertModuleSignal("sram0", "sram0_addr", "in", "22");
        assertModuleSignal("sram0", "sram0_be", "in", "64");
        assertNotNull(moduleGroup("m_axi"));
        assertNull(moduleGroup("s_axil"));
        assertNotNull(moduleGroup("static_io"));
        capture("07-mixed-width-static-prefix.png");
        goMaintenance();
        checkBox("Enable self-test").click();
        awaitIdle();
        assertFalse(wizard.get().controller().valid());
        capture("08-invalid-selftest-diagnostics.png");
        checkBox("Enable self-test").click();
        awaitIdle();
        assertTrue(wizard.get().controller().valid());
        bot.button("< Back").click();
        bot.button("< Back").click();
        bot.button("< Back").click();
        bot.comboBoxWithLabel("Configuration mode:").setSelection("CSR");
        awaitIdle();
        assertNotNull(moduleGroup("s_axil"));
        assertNull(moduleGroup("static_io"));
        next();
        bot.comboBoxWithLabel("Selected port:").setSelection(1);
        checkBox("Enabled").click();
        awaitIdle();
        assertFalse(port(wizard.get().controller().config(), 1).get("enabled").getAsBoolean());
        capture("07b-sparse-csr.png");
        goReview();
        selectAnalysisTab(AnalysisCanvas.View.MEMORY_MAP, "Memory Map", "14-review-memory-mixed.png");
        assertEquals(4, bot.table().rowCount());
        bot.button("< Back").click();
        bot.button("< Back").click();
        bot.button("< Back").click();
        bot.button("< Back").click();
        setText("Physical ports:", "64");
        assertEquals(64, wizard.get().controller().config().getAsJsonArray("ports").size());
        next();
        assertEquals(64, bot.table().rowCount());
        assertEquals(68, wizard.get().controller().module().getAsJsonArray("groups").size());
        assertEquals(7, moduleGroup("sram63").getAsJsonArray("signals").size());
        assertFalse(moduleGroup("sram63").get("expanded").getAsBoolean());
        assertFalse(moduleGroup("m_axi").get("expanded").getAsBoolean());
        capture("09-ports-n64.png");
        goReview();
        selectAnalysisTab(AnalysisCanvas.View.MEMORY_MAP, "Memory Map", "15-review-memory-n64.png");
        assertEquals(64, bot.table().rowCount());
    }

    @Test
    public void automaticAndManualTimingAndInvalidInputRepair() throws Exception {
        openWizard(null, false);
        awaitIdle();
        goClock();
        setText("Port clock (Hz):", "400000");
        JsonObject config = wizard.get().controller().config();
        assertEquals(750, config.get("target_period_cycles").getAsInt());
        assertTrue(wizard.get().controller().automaticTiming());
        setText("Target period (ui_clk cycles):", "700");
        assertFalse(wizard.get().controller().automaticTiming());
        setText("Port clock (Hz):", "450000");
        assertEquals(700, wizard.get().controller().config().get("target_period_cycles").getAsInt());
        bot.button("Derive / reset periods").click();
        awaitIdle();
        assertTrue(wizard.get().controller().config().get("target_period_cycles").getAsInt() != 700);
        capture("10-clock-manual-derived.png");
        bot.button("< Back").click();
        bot.button("< Back").click();
        setText("Module name:", "");
        assertFalse(wizard.get().controller().valid());
        bot.button("Next >").click();
        bot.button("Next >").click();
        bot.button("Next >").click();
        bot.button("Next >").click();
        assertAnalysisView(AnalysisCanvas.View.TIMING, "Timing", false);
        assertAnalysisView(AnalysisCanvas.View.SLOT_BUDGET, "Slot Budget", false);
        assertAnalysisView(AnalysisCanvas.View.MEMORY_MAP, "Memory Map", false);
        assertAnalysisView(AnalysisCanvas.View.BANDWIDTH, "Bandwidth", false);
        bot.button("< Back").click();
        bot.button("< Back").click();
        bot.button("< Back").click();
        bot.button("< Back").click();
        setText("Module name:", "repaired_module");
        assertTrue(wizard.get().controller().valid());
    }

    @Test
    public void safeOverwriteAndSaveReopenUsesManualAddressing() throws Exception {
        JsonObject initial = allocateInitial(32, 19);
        Path saved = temporary.resolve("saved.json");
        try (BackendService service = service()) {
            assertTrue(service.save(initial, saved, false).get("saved").getAsBoolean());
            assertFalse(service.save(initial, saved, false).get("saved").getAsBoolean());
            assertTrue(service.save(initial, saved, true).get("saved").getAsBoolean());
        }
        AtomicReference<BackendWindow> window = new AtomicReference<>();
        display.asyncExec(() -> {
            ConfigFileChooser files = new ConfigFileChooser() {
                @Override
                public Path open(Shell shell) {
                    return saved;
                }

                @Override
                public Path save(Shell shell, Path current) {
                    return saved;
                }
            };
            DirectoryChooser directories = (shell, title, initialPath) -> temporary.resolve("generated");
            BackendWindow value = new BackendWindow(provider, descriptorPath, "python3", temporary.resolve("generated"),
                    files, directories);
            window.set(value);
            value.open();
            value.openConfiguration(saved);
        });
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return !window.get().busy() && window.get().configuration() != null;
            }

            @Override
            public String getFailureMessage() {
                return "Configuration did not open";
            }
        }, 30_000);
        assertNotNull(window.get().modulePreview());
        assertNotNull(window.get().module());
        assertEquals("hades_sram_user", window.get().module().get("name").getAsString());
        assertEquals(5, window.get().module().getAsJsonArray("groups").size());
        display.asyncExec(() -> window.get().editConfiguration());
        SWTBotShell wizardShell = bot.shell("HADES-SRAM Configuration");
        wizardShell.activate();
        BackendWizard active = window.get().activeWizard();
        assertNotNull(active);
        awaitIdle(active);
        assertFalse(active.controller().automaticAddressing());
        assertFalse(active.controller().automaticTiming());
        active.controller().close();
        display.syncExec(() -> window.get().close());
    }

    @Test
    public void finishRefusesExistingOutputUnlessForceIsExplicit() throws Exception {
        Path output = temporary.resolve("generated");
        try (BackendService service = service()) {
            JsonObject generated = service.generate(service.schema().getAsJsonObject("defaults"), output, false, false);
            assertEquals("generated", generated.get("outcome").getAsString());
        }
        openWizard(null, false);
        awaitIdle();
        next();
        next();
        next();
        next();
        bot.button("Finish").click();
        awaitIdle();
        assertEquals("error", wizard.get().generatedResult().get("outcome").getAsString());
        assertTrue(bot.shell("HADES-SRAM Configuration").isVisible());
        checkBox("Overwrite existing managed artifacts").click();
        awaitIdle();
        checkBox("Copy complete integration sources").click();
        awaitIdle();
        assertFalse(wizard.get().controller().fullCopy());
        bot.button("Finish").click();
        awaitClosed();
        assertEquals("generated", wizard.get().generatedResult().get("outcome").getAsString());
        assertFalse(Files.exists(output.resolve("static_rtl.f")));
        assertFalse(Files.exists(output.resolve("logical/hades_sram_top.sv")));
        assertTrue(Files.isRegularFile(output.resolve("wrapper.f")));
        assertTrue(Files.isRegularFile(output.resolve("report/analysis.json")));
        assertTrue(Files.isRegularFile(output.resolve("constraints/sram0_strict.xdc")));
    }

    private void openWizard(JsonObject initial, boolean loaded) {
        Path output = temporary.resolve("generated");
        display.asyncExec(() -> {
            BackendWizard value = new BackendWizard(provider, descriptorPath, "python3", initial, loaded, output,
                    (shell, title, initialPath) -> output);
            wizard.set(value);
            WizardDialog wizardDialog = new WizardDialog(null, value);
            wizardDialog.setPageSize(1120, 860);
            dialog.set(wizardDialog);
            wizardDialog.open();
        });
        wizardShell = bot.shell("HADES-SRAM Configuration");
        wizardShell.activate();
        awaitIdle();
        assertTrue(wizard.get().controller().backendError() + " " + wizard.get().controller().diagnostics(),
                wizard.get().controller().valid());
    }

    private JsonObject allocateInitial(int width, int depth) throws Exception {
        try (BackendService service = service()) {
            JsonObject config = service.schema().getAsJsonObject("defaults");
            config.getAsJsonArray("ports").get(0).getAsJsonObject().addProperty("width", width);
            JsonArray depths = new JsonArray();
            depths.add(depth);
            return service.allocate(config, depths).getAsJsonObject("config");
        }
    }

    private BackendService service() throws Exception {
        return BackendService.launch(provider, descriptorPath, "python3", ProviderJson.gson(), Duration.ofSeconds(30));
    }

    private void next() {
        bot.button("Next >").click();
        awaitIdle();
        assertNotNull(wizard.get().modulePreview());
    }

    private void goClock() {
        next();
        next();
    }

    private void goMaintenance() {
        next();
        next();
    }

    private void goReview() {
        next();
        next();
        next();
    }

    private void setText(String label, String value) {
        bot.textWithLabel(label).setText(value);
        awaitIdle();
    }

    private JsonObject port(JsonObject config, int index) {
        return config.getAsJsonArray("ports").get(index).getAsJsonObject();
    }

    private JsonObject moduleGroup(String id) {
        JsonObject module = wizard.get().controller().module();
        if (module == null) {
            return null;
        }
        for (JsonElement item : module.getAsJsonArray("groups")) {
            JsonObject group = item.getAsJsonObject();
            if (id.equals(group.get("id").getAsString())) {
                return group;
            }
        }
        return null;
    }

    private org.eclipse.swt.widgets.TreeItem treeGroup(ModulePortPreview preview, String id) {
        for (org.eclipse.swt.widgets.TreeItem item : preview.getTree().getItems()) {
            if (id.equals(item.getData("modulepreview.groupId"))) {
                return item;
            }
        }
        return null;
    }

    private void assertPreviewSignal(ModulePortPreview preview, String groupId, String name, String width) {
        var group = preview.getModel().groups().stream().filter(item -> item.id().equals(groupId)).findFirst()
                .orElse(null);
        assertNotNull(group);
        var signal = group.signals().stream().filter(item -> item.name().equals(name)).findFirst().orElse(null);
        assertNotNull(signal);
        assertEquals(width, signal.width());
    }

    private void assertModuleSignal(String groupId, String name, String direction, String width) {
        JsonObject group = moduleGroup(groupId);
        assertNotNull("Missing module group " + groupId, group);
        JsonObject match = null;
        for (JsonElement item : group.getAsJsonArray("signals")) {
            JsonObject signal = item.getAsJsonObject();
            if (name.equals(signal.get("name").getAsString())) {
                match = signal;
                break;
            }
        }
        assertNotNull("Missing signal " + groupId + "/" + name, match);
        assertEquals(direction, match.get("direction").getAsString());
        assertEquals(width, match.get("width").getAsString());
    }

    private JsonObject analysisPort(int index) {
        return wizard.get().controller().analysis().getAsJsonArray("ports").get(index).getAsJsonObject();
    }

    private void awaitIdle() {
        awaitIdle(wizard.get());
    }

    private void awaitIdle(BackendWizard value) {
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return value != null && value.controller().config() != null && !value.controller().loading();
            }

            @Override
            public String getFailureMessage() {
                return "Backend did not become idle";
            }
        }, 30_000);
    }

    private void awaitClosed() {
        SWTBotShell completion = bot.shell("Generation Complete");
        assertTrue(completion.isVisible());
        completion.bot().button("OK").click();
        bot.waitUntil(org.eclipse.swtbot.swt.finder.waits.Conditions.shellCloses(wizardShell), 30_000);
    }

    private void selectAnalysisTab(AnalysisCanvas.View view, String label, String screenshot) {
        assertAnalysisView(view, label, true);
        capture(screenshot);
    }

    private void assertAnalysisView(AnalysisCanvas.View view, String label, boolean expectRows) {
        bot.tabItem(label).activate();
        AnalysisCanvas canvas = wizard.get().analysisCanvas(view);
        assertNotNull(canvas);
        bot.waitUntil(new DefaultCondition() {
            @Override
            public boolean test() {
                return canvas.lastPaintRevision() == wizard.get().controller().revision();
            }

            @Override
            public String getFailureMessage() {
                return view + " did not paint current revision " + wizard.get().controller().revision()
                        + "; last=" + canvas.lastPaintRevision() + "; error=" + canvas.lastError();
            }
        }, 30_000);
        assertTrue(canvas.paintCount() > 0);
        assertNull(canvas.lastError());
        assertEquals(expectRows, bot.table().rowCount() > 0);
    }

    private org.eclipse.swtbot.swt.finder.widgets.SWTBotCheckBox checkBox(String label) {
        display.syncExec(() -> focusCheck(dialog.get().getShell(), label));
        return label.equals("Enabled") ? bot.checkBox(1) : bot.checkBox(label);
    }

    private boolean focusCheck(org.eclipse.swt.widgets.Composite parent, String label) {
        for (org.eclipse.swt.widgets.Control child : parent.getChildren()) {
            if (child instanceof org.eclipse.swt.widgets.Button button && label.equals(button.getText().replace("&", ""))) {
                org.eclipse.swt.widgets.Control ancestor = button.getParent();
                while (ancestor != null) {
                    if (ancestor instanceof org.eclipse.swt.custom.ScrolledComposite scrolled) {
                        scrolled.showControl(button);
                    }
                    ancestor = ancestor.getParent();
                }
                button.setFocus();
                return true;
            }
            if (child instanceof org.eclipse.swt.widgets.Composite composite && focusCheck(composite, label)) {
                return true;
            }
        }
        return false;
    }

    private void capture(String name) {
        try {
            NativeScreenshot.capture("HADES-SRAM Configuration", Path.of("target/screenshots", name));
        } catch (Exception e) {
            throw new AssertionError("Could not capture native wizard screenshot", e);
        }
    }
}
