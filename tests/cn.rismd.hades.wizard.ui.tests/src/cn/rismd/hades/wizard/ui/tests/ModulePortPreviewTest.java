package cn.rismd.hades.wizard.ui.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import cn.rismd.hades.wizard.modulepreview.ModuleModel;
import cn.rismd.hades.wizard.modulepreview.ModulePortPreview;
import cn.rismd.hades.wizard.modulepreview.PortDirection;
import cn.rismd.hades.wizard.modulepreview.PortGroup;
import cn.rismd.hades.wizard.modulepreview.PortSignal;

public class ModulePortPreviewTest {
    private Display display;
    private Thread eventThread;
    private Shell shell;
    private ModulePortPreview preview;

    @Before
    public void startDisplay() throws Exception {
        var ready = new CountDownLatch(1);
        eventThread = new Thread(() -> {
            display = new Display();
            shell = new Shell(display);
            shell.setText("Module preview test");
            shell.setLayout(new FillLayout());
            preview = new ModulePortPreview(shell, SWT.NONE);
            shell.setSize(1000, 800);
            shell.open();
            shell.layout(true, true);
            ready.countDown();
            while (!display.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
        }, "Module preview test display");
        eventThread.start();
        assertTrue("Display did not start", ready.await(10, TimeUnit.SECONDS));
    }

    @After
    public void disposeDisplay() throws Exception {
        if (display != null && !display.isDisposed()) {
            display.syncExec(() -> {
                if (shell != null && !shell.isDisposed()) {
                    shell.dispose();
                }
                display.dispose();
            });
        }
        if (eventThread != null) {
            eventThread.join(10_000);
            assertFalse("Display thread did not stop", eventThread.isAlive());
        }
    }

    @Test
    public void rendersModelAndExactSynchronizedTreeValues() throws Exception {
        ModuleModel model = sampleModel("example_module", false, "32");
        loadModel(model, 7);

        assertSame(model, uiValue(() -> preview.getModel()));
        assertEquals(7, uiValue(() -> preview.getRevision()).longValue());
        assertEquals("Module Port Preview", uiValue(() -> preview.getContentGroup().getText()));
        assertTrue(uiValue(() -> preview.getContentGroup().isVisible()));
        assertEquals("example_module", uiValue(() -> preview.getModuleNameLabel().getText()));
        assertEquals("2 port groups loaded.", uiValue(() -> preview.getStatusLabel().getText()));
        assertTrue(uiValue(() -> (preview.getCanvas().getStyle() & SWT.DOUBLE_BUFFERED) != 0));
        assertEquals(List.of("Zoom In", "Zoom Out", "Fit", "Reset"), uiValue(() -> List.of(
                preview.getZoomInButton().getText(), preview.getZoomOutButton().getText(), preview.getFitButton().getText(),
                preview.getResetButton().getText())));
        assertEquals("100%", uiValue(() -> preview.getZoomLabel().getText()));
        assertEquals(List.of("Signal / Group", "Direction", "Width", "Clock Domain"), uiValue(() -> {
            var columns = new ArrayList<String>();
            for (int i = 0; i < preview.getTree().getColumnCount(); i++) {
                columns.add(preview.getTree().getColumn(i).getText());
            }
            return List.copyOf(columns);
        }));

        List<TreeRow> rows = treeRows();
        assertEquals(2, rows.size());
        assertEquals(new TreeRow("Clocking", "", "", "core_clk", true, 2), rows.get(0));
        assertEquals(new TreeRow("Data", "", "", "port_clk", false, 2), rows.get(1));
        assertEquals(List.of("payload", "INPUT", "32", "port_clk"), signalRow(1, 0));
        assertEquals(List.of("result", "OUTPUT", "32", "port_clk"), signalRow(1, 1));
        assertEquals("Data value", uiValue(() -> ((PortSignal) preview.getTree().getItem(1).getItem(0)
                .getData("modulepreview.signal")).description()));
        assertNull(uiValue(() -> preview.getLastPaintError()));
        assertTrue(uiValue(() -> preview.getPaintCount()) > 0);
    }

    @Test
    public void canvasHitTargetSynchronizesDiagramAndTreeExpansion() throws Exception {
        loadModel(sampleModel("example_module", false, "32"), 1);
        Rectangle target = uiValue(() -> preview.getGroupExpandHitTarget("data"));
        assertNotNull(target);
        int paintCount = uiValue(() -> preview.getPaintCount());

        clickCanvas(target.x + target.width / 2, target.y + target.height / 2);
        awaitPaint(1, paintCount + 1);
        assertTrue(uiValue(() -> preview.isGroupExpanded("data")));
        assertTrue(treeRows().get(1).expanded());

        paintCount = uiValue(() -> preview.getPaintCount());
        target = uiValue(() -> preview.getGroupExpandHitTarget("data"));
        clickCanvas(target.x + target.width / 2, target.y + target.height / 2);
        awaitPaint(1, paintCount + 1);
        assertFalse(uiValue(() -> preview.isGroupExpanded("data")));
        assertFalse(treeRows().get(1).expanded());
    }

    @Test
    public void expansionChoicesSurviveModelUpdatesWhileValuesRefresh() throws Exception {
        loadModel(sampleModel("example_module", false, "32"), 1);
        ui(() -> {
            preview.setGroupExpanded("clocks", false);
            preview.setGroupExpanded("data", true);
        });

        var updated = new ModuleModel("example_module_v2", List.of(
                new PortGroup("clocks", "Updated Clocking", "core_clk", true,
                        List.of(new PortSignal("clk", PortDirection.INPUT, "1", "Updated clock"))),
                new PortGroup("data", "Updated Data", "port_clk", false,
                        List.of(new PortSignal("payload", PortDirection.INPUT, "64", "Wider value")))));
        loadModel(updated, 2);

        assertFalse(uiValue(() -> preview.isGroupExpanded("clocks")));
        assertTrue(uiValue(() -> preview.isGroupExpanded("data")));
        List<TreeRow> rows = treeRows();
        assertEquals("Updated Clocking", rows.get(0).name());
        assertFalse(rows.get(0).expanded());
        assertEquals("Updated Data", rows.get(1).name());
        assertTrue(rows.get(1).expanded());
        assertEquals(List.of("payload", "INPUT", "64", "port_clk"), signalRow(1, 0));
    }

    @Test
    public void largeSixtyFourGroupModelRemainsNavigable() throws Exception {
        var groups = new ArrayList<PortGroup>();
        for (int i = 0; i < 64; i++) {
            groups.add(new PortGroup("group-" + i, "Group " + i, "clk_" + i % 2, false, List.of(
                    new PortSignal("input_" + i, PortDirection.INPUT, "8", "Input " + i),
                    new PortSignal("output_" + i, PortDirection.OUTPUT, "8", "Output " + i))));
        }
        loadModel(new ModuleModel("large_module", groups), 64);
        assertEquals(64, treeRows().size());

        ui(() -> preview.setGroupExpanded("group-63", true));
        TreeRow last = treeRows().get(63);
        assertTrue(last.expanded());
        assertEquals(2, last.childCount());
        assertEquals(List.of("output_63", "OUTPUT", "8", "clk_1"), signalRow(63, 1));
        assertNotNull(uiValue(() -> preview.getGroupExpandHitTarget("group-63")));

        int paintCount = uiValue(() -> preview.getPaintCount());
        ui(() -> preview.fit());
        awaitPaint(64, paintCount + 1);
        assertTrue(uiValue(() -> preview.getZoom()) >= ModulePortPreview.MIN_ZOOM);
        assertNull(uiValue(() -> preview.getLastPaintError()));
    }

    @Test
    public void leftDragPansWithoutRebuildingTreeOrChangingZoom() throws Exception {
        loadModel(sampleModel("example_module", false, "32"), 3);
        ui(() -> preview.zoomIn());
        ViewState before = viewState();
        ModuleModel model = uiValue(() -> preview.getModel());
        TreeItem firstItem = uiValue(() -> preview.getTree().getItem(0));
        int paintCount = uiValue(() -> preview.getPaintCount());

        dragCanvas(600, 500, 650, 540);
        awaitPaint(3, paintCount + 1);

        assertEquals(before.panX() + 50.0, uiValue(() -> preview.getPanX()), 0.0);
        assertEquals(before.panY() + 40.0, uiValue(() -> preview.getPanY()), 0.0);
        assertEquals(before.zoom(), uiValue(() -> preview.getZoom()), 0.0);
        assertSame(model, uiValue(() -> preview.getModel()));
        assertSame(firstItem, uiValue(() -> preview.getTree().getItem(0)));
    }

    @Test
    public void buttonsWheelKeyboardAndZoomBoundsStayConsistent() throws Exception {
        loadModel(sampleModel("example_module", false, "32"), 4);
        int paintCount = uiValue(() -> preview.getPaintCount());

        ui(() -> click(preview.getZoomInButton()));
        assertEquals(1.25, uiValue(() -> preview.getZoom()), 0.000001);
        assertEquals("125%", uiValue(() -> preview.getZoomLabel().getText()));
        ui(() -> click(preview.getZoomOutButton()));
        assertEquals(1.0, uiValue(() -> preview.getZoom()), 0.000001);

        double beforeWheel = uiValue(() -> preview.getZoom());
        ui(() -> {
            Event event = canvasEvent(SWT.MouseWheel, 120, 120);
            event.stateMask = SWT.CTRL;
            event.count = 1;
            preview.getCanvas().notifyListeners(SWT.MouseWheel, event);
        });
        assertTrue(uiValue(() -> preview.getZoom()) > beforeWheel);

        double beforeKey = uiValue(() -> preview.getZoom());
        ui(() -> {
            Event event = canvasEvent(SWT.KeyDown, 0, 0);
            event.character = '+';
            event.keyCode = '=';
            preview.getCanvas().notifyListeners(SWT.KeyDown, event);
        });
        assertTrue(uiValue(() -> preview.getZoom()) > beforeKey);

        ui(() -> {
            for (int i = 0; i < 30; i++) {
                preview.zoomIn();
            }
        });
        assertEquals(ModulePortPreview.MAX_ZOOM, uiValue(() -> preview.getZoom()), 0.0);
        assertFalse(uiValue(() -> preview.getZoomInButton().isEnabled()));
        assertEquals("400%", uiValue(() -> preview.getZoomLabel().getText()));

        ui(() -> {
            for (int i = 0; i < 50; i++) {
                preview.zoomOut();
            }
        });
        assertEquals(ModulePortPreview.MIN_ZOOM, uiValue(() -> preview.getZoom()), 0.0);
        assertFalse(uiValue(() -> preview.getZoomOutButton().isEnabled()));
        assertEquals("5%", uiValue(() -> preview.getZoomLabel().getText()));
        awaitPaint(4, paintCount + 1);
    }

    @Test
    public void viewSurvivesRefreshAndFitResetAndTransformsAgree() throws Exception {
        loadModel(sampleModel("example_module", false, "32"), 10);
        ui(() -> {
            preview.zoomIn();
            preview.panBy(37, -19);
        });
        ViewState before = viewState();

        loadModel(sampleModel("updated_module", true, "64"), 11);
        assertEquals(before, viewState());
        ModulePortPreview.DiagramPoint screen = uiValue(() -> preview.modelToScreen(123.25, 456.5));
        ModulePortPreview.DiagramPoint model = uiValue(() -> preview.screenToModel(screen.x(), screen.y()));
        assertEquals(123.25, model.x(), 0.000001);
        assertEquals(456.5, model.y(), 0.000001);

        int paintCount = uiValue(() -> preview.getPaintCount());
        ui(() -> preview.fit());
        awaitPaint(11, paintCount + 1);
        assertTrue(viewState().zoom() >= ModulePortPreview.MIN_ZOOM);
        assertTrue(viewState().zoom() <= ModulePortPreview.MAX_ZOOM);
        assertFalse(before.equals(viewState()));

        ui(() -> preview.reset());
        assertEquals(new ViewState(1.0, 0.0, 0.0), viewState());
        assertEquals("100%", uiValue(() -> preview.getZoomLabel().getText()));
    }

    @Test
    public void repaintTracksRevisionAndPaintCount() throws Exception {
        loadModel(sampleModel("example_module", false, "32"), 20);
        int firstCount = uiValue(() -> preview.getPaintCount());
        assertEquals(20, uiValue(() -> preview.getLastPaintRevision()).longValue());

        loadModel(sampleModel("example_module", true, "32"), 21);
        assertEquals(21, uiValue(() -> preview.getLastPaintRevision()).longValue());
        assertTrue(uiValue(() -> preview.getPaintCount()) > firstCount);

        int beforeRedraw = uiValue(() -> preview.getPaintCount());
        ui(() -> preview.getCanvas().redraw());
        awaitPaint(21, beforeRedraw + 1);
        assertEquals(21, uiValue(() -> preview.getLastPaintRevision()).longValue());
    }

    @Test
    public void nullLoadingInvalidAndEmptyStatesKeepPreviewVisible() throws Exception {
        ui(() -> preview.setStatus("Loading module metadata"));
        assertEquals("Loading module metadata", uiValue(() -> preview.getStatusLabel().getText()));
        assertTrue(uiValue(() -> preview.getContentGroup().isVisible()));
        assertEquals(0, treeRows().size());

        loadModel(sampleModel("example_module", false, "32"), 30);
        ui(() -> preview.setGroupExpanded("data", true));
        int paintCount = uiValue(() -> preview.getPaintCount());
        ui(() -> {
            preview.setModel(null, 31);
            preview.setStatus("Invalid configuration: width is unavailable");
            preview.zoomIn();
            preview.zoomOut();
            preview.fit();
            preview.reset();
        });
        awaitPaint(31, paintCount + 1);
        assertNull(uiValue(() -> preview.getModel()));
        assertEquals(31, uiValue(() -> preview.getRevision()).longValue());
        assertEquals("No module loaded", uiValue(() -> preview.getModuleNameLabel().getText()));
        assertEquals("Invalid configuration: width is unavailable", uiValue(() -> preview.getStatusLabel().getText()));
        assertTrue(uiValue(() -> preview.getContentGroup().isVisible()));
        assertTrue(uiValue(() -> preview.getCanvas().isVisible()));
        assertEquals(0, treeRows().size());

        loadModel(new ModuleModel("empty_module", List.of()), 32);
        assertEquals("empty_module", uiValue(() -> preview.getModuleNameLabel().getText()));
        assertEquals("No port groups in module.", uiValue(() -> preview.getStatusLabel().getText()));
        assertEquals(0, treeRows().size());
        assertNull(uiValue(() -> preview.getLastPaintError()));

        loadModel(sampleModel("example_module", false, "32"), 33);
        assertTrue(uiValue(() -> preview.isGroupExpanded("data")));
        assertTrue(treeRows().get(1).expanded());
    }

    @Test
    public void disposingParentDisposesNativeChildren() {
        List<Boolean> disposed = uiValue(() -> {
            Shell localShell = new Shell(display);
            localShell.setLayout(new FillLayout());
            ModulePortPreview localPreview = new ModulePortPreview(localShell, SWT.NONE);
            Canvas canvas = localPreview.getCanvas();
            Tree tree = localPreview.getTree();
            localShell.dispose();
            return List.of(localPreview.isDisposed(), canvas.isDisposed(), tree.isDisposed());
        });
        assertEquals(List.of(true, true, true), disposed);
    }

    @Test
    public void modelValidatesAndDefensivelyCopiesInputs() {
        var signals = new ArrayList<PortSignal>();
        signals.add(new PortSignal("signal", PortDirection.INPUT, 8, "Description"));
        var groups = new ArrayList<PortGroup>();
        groups.add(new PortGroup("group", "Group", "clk", true, signals));
        ModuleModel model = new ModuleModel("module", groups);

        signals.add(new PortSignal("other", PortDirection.OUTPUT, 1, "Other"));
        groups.add(new PortGroup("other", "Other", "", false, List.of()));
        assertEquals(1, model.groups().size());
        assertEquals(1, model.groups().get(0).signals().size());
        assertThrows(UnsupportedOperationException.class,
                () -> model.groups().add(new PortGroup("third", "Third", "", false, List.of())));
        assertThrows(IllegalArgumentException.class, () -> new ModuleModel("duplicate", List.of(
                new PortGroup("same", "One", "", false, List.of()),
                new PortGroup("same", "Two", "", false, List.of()))));
        assertThrows(IllegalArgumentException.class, () -> new ModuleModel(" ", List.of()));
        assertThrows(NullPointerException.class, () -> new PortSignal("bad", null, "1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new PortSignal("bad", PortDirection.INPUT, 0, ""));
        assertThrows(UnsupportedOperationException.class,
                () -> new PortGroup("group", "Group", "", false, List.of()).signals().add(null));
    }

    private ModuleModel sampleModel(String name, boolean dataExpanded, String width) {
        return new ModuleModel(name, List.of(
                new PortGroup("clocks", "Clocking", "core_clk", true, List.of(
                        new PortSignal("clk", PortDirection.INPUT, "1", "Clock"),
                        new PortSignal("reset_n", PortDirection.INPUT, "1", "Reset"))),
                new PortGroup("data", "Data", "port_clk", dataExpanded, List.of(
                        new PortSignal("payload", PortDirection.INPUT, width, "Data value"),
                        new PortSignal("result", PortDirection.OUTPUT, width, "Result value")))));
    }

    private void loadModel(ModuleModel model, long revision) throws Exception {
        int paintCount = uiValue(() -> preview.getPaintCount());
        ui(() -> preview.setModel(model, revision));
        awaitPaint(revision, paintCount + 1);
    }

    private void awaitPaint(long revision, int minimumCount) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (uiValue(() -> preview.getLastPaintRevision() == revision && preview.getPaintCount() >= minimumCount)) {
                assertNull(uiValue(() -> preview.getLastPaintError()));
                return;
            }
            Thread.sleep(10);
        }
        String state = uiValue(() -> "revision=" + preview.getLastPaintRevision() + ", count=" + preview.getPaintCount()
                + ", error=" + preview.getLastPaintError());
        fail("Timed out waiting for paint revision " + revision + " and count " + minimumCount + ": " + state);
    }

    private List<TreeRow> treeRows() {
        return uiValue(() -> {
            var rows = new ArrayList<TreeRow>();
            for (TreeItem item : preview.getTree().getItems()) {
                rows.add(new TreeRow(item.getText(0), item.getText(1), item.getText(2), item.getText(3),
                        item.getExpanded(), item.getItemCount()));
            }
            return List.copyOf(rows);
        });
    }

    private List<String> signalRow(int groupIndex, int signalIndex) {
        return uiValue(() -> {
            TreeItem item = preview.getTree().getItem(groupIndex).getItem(signalIndex);
            return List.of(item.getText(0), item.getText(1), item.getText(2), item.getText(3));
        });
    }

    private ViewState viewState() {
        return uiValue(() -> new ViewState(preview.getZoom(), preview.getPanX(), preview.getPanY()));
    }

    private void clickCanvas(int x, int y) {
        ui(() -> {
            Event down = canvasEvent(SWT.MouseDown, x, y);
            down.button = 1;
            preview.getCanvas().notifyListeners(SWT.MouseDown, down);
            Event up = canvasEvent(SWT.MouseUp, x, y);
            up.button = 1;
            preview.getCanvas().notifyListeners(SWT.MouseUp, up);
        });
    }

    private void dragCanvas(int startX, int startY, int endX, int endY) {
        ui(() -> {
            Event down = canvasEvent(SWT.MouseDown, startX, startY);
            down.button = 1;
            preview.getCanvas().notifyListeners(SWT.MouseDown, down);
            Event move = canvasEvent(SWT.MouseMove, endX, endY);
            move.stateMask = SWT.BUTTON1;
            preview.getCanvas().notifyListeners(SWT.MouseMove, move);
            Event up = canvasEvent(SWT.MouseUp, endX, endY);
            up.button = 1;
            preview.getCanvas().notifyListeners(SWT.MouseUp, up);
        });
    }

    private Event canvasEvent(int type, int x, int y) {
        Event event = new Event();
        event.type = type;
        event.display = display;
        event.widget = preview.getCanvas();
        event.x = x;
        event.y = y;
        return event;
    }

    private static void click(Button button) {
        Event event = new Event();
        event.type = SWT.Selection;
        event.display = button.getDisplay();
        event.widget = button;
        button.notifyListeners(SWT.Selection, event);
    }

    private void ui(Runnable action) {
        var failure = new AtomicReference<Throwable>();
        display.syncExec(() -> {
            try {
                action.run();
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError("SWT UI action failed", failure.get());
        }
    }

    private <T> T uiValue(Supplier<T> action) {
        var value = new AtomicReference<T>();
        ui(() -> value.set(action.get()));
        return value.get();
    }

    private record TreeRow(String name, String direction, String width, String clockDomain, boolean expanded,
            int childCount) {
    }

    private record ViewState(double zoom, double panX, double panY) {
    }
}
