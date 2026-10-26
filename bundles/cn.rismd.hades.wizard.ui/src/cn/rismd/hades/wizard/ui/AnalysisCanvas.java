package cn.rismd.hades.wizard.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class AnalysisCanvas extends Canvas {
    public enum View {
        TIMING,
        SLOT_BUDGET,
        MEMORY_MAP,
        BANDWIDTH
    }

    private final View view;
    private JsonObject analysis;
    private long revision = -1;
    private long lastPaintRevision = -1;
    private int paintCount;
    private String lastError;

    public AnalysisCanvas(Composite parent, View view) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.view = view;
        setData("hades.analysisView", view.name());
        addPaintListener(event -> paint(event.gc));
        setToolTipText(accessibleDescription());
        getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
            @Override
            public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) {
                event.result = accessibleDescription();
            }

            @Override
            public void getDescription(org.eclipse.swt.accessibility.AccessibleEvent event) {
                event.result = accessibleDescription();
            }
        });
    }

    public void setAnalysis(JsonObject analysis, long revision) {
        this.analysis = analysis == null ? null : analysis.deepCopy();
        this.revision = revision;
        setData("hades.analysisRevision", revision);
        setToolTipText(accessibleDescription());
        redraw();
    }

    public long lastPaintRevision() {
        return lastPaintRevision;
    }

    public int paintCount() {
        return paintCount;
    }

    public String lastError() {
        return lastError;
    }

    public Point preferredSize() {
        if (view == View.MEMORY_MAP) {
            int ports = ports().size();
            return new Point(920, Math.max(220, ports * 22 + 70));
        }
        return new Point(900, view == View.TIMING ? 300 : 250);
    }

    private String accessibleDescription() {
        if (analysis == null) {
            return view + ": no current valid analysis; visualization cleared. Exact-value table remains available.";
        }
        return switch (view) {
            case TIMING -> "Exact frontend request and response waveform for latency "
                    + text(analysis.getAsJsonObject("timing"), "read_latency_cycles") + " cycles / "
                    + text(analysis.getAsJsonObject("timing"), "read_latency_ns_text")
                    + " ns. Not backend or MIG completion timing; posted writes and analytical only.";
            case SLOT_BUDGET -> "TDMA allocation: " + text(analysis.getAsJsonObject("timing"), "budget_used_cycles")
                    + " used of " + text(analysis.getAsJsonObject("timing"), "target_period_cycles")
                    + " cycles; strict pass " + text(analysis.getAsJsonObject("timing"), "strict_budget_passes")
                    + ". Nominal slot table is also available.";
            case MEMORY_MAP -> "Backend base and window plot for " + ports().size()
                    + " ports. Raw bases, ends, tails, padding, and capacities are shown in the scrollable table.";
            case BANDWIDTH -> "Configured demand, not measured throughput: frontend payload "
                    + text(analysis.getAsJsonObject("bandwidth"), "frontend_payload_bytes_per_second")
                    + " bytes/s; allocated backend "
                    + text(analysis.getAsJsonObject("bandwidth"), "allocated_backend_bytes_per_second") + " bytes/s.";
        };
    }

    private void paint(GC gc) {
        lastError = null;
        try {
            Rectangle area = getClientArea();
            gc.setBackground(color(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(area);
            gc.setForeground(color(SWT.COLOR_WIDGET_FOREGROUND));
            if (analysis == null) {
                drawLabel(gc, "No current valid analysis — visualization cleared; use the exact-value table.", 12, 12);
            } else {
                switch (view) {
                    case TIMING -> paintTiming(gc, area);
                    case SLOT_BUDGET -> paintSlots(gc, area);
                    case MEMORY_MAP -> paintMemory(gc, area);
                    case BANDWIDTH -> paintBandwidth(gc, area);
                }
            }
            paintCount++;
            lastPaintRevision = revision;
        } catch (RuntimeException e) {
            lastError = e.toString();
            gc.setForeground(color(SWT.COLOR_RED));
            drawLabel(gc, "Visualization error; use the exact-value table.", 12, 12);
        }
    }

    private void paintTiming(GC gc, Rectangle area) {
        JsonObject clock = analysis.getAsJsonObject("clock");
        JsonObject timing = analysis.getAsJsonObject("timing");
        int latency = integer(timing, "read_latency_cycles");
        int left = 105;
        int cycleWidth = Math.min(150, Math.max(70, (area.width - 170) / 3));
        int top = 55;
        drawLabel(gc, "port_clk", 12, top - 10);
        drawLabel(gc, "request", 12, top + 55);
        drawLabel(gc, "rvalid", 12, top + 105);
        for (int cycle = 0; cycle <= 2; cycle++) {
            int x = left + cycle * cycleWidth;
            drawLabel(gc, "cycle " + cycle, x - 12, 18);
            gc.drawLine(x, 35, x, top + 140);
            int next = x + cycleWidth;
            gc.drawLine(x, top, x + cycleWidth / 2, top);
            gc.drawLine(x + cycleWidth / 2, top, x + cycleWidth / 2, top + 30);
            gc.drawLine(x + cycleWidth / 2, top + 30, next, top + 30);
            gc.drawLine(next, top + 30, next, top);
        }
        int requestX = left;
        int responseX = left + latency * cycleWidth;
        gc.drawLine(requestX, top + 70, requestX, top + 45);
        drawLabel(gc, "request edge", requestX - 35, top + 75);
        gc.drawLine(left, top + 60, left + 2 * cycleWidth, top + 60);
        gc.drawLine(responseX, top + 120, responseX, top + 90);
        drawLabel(gc, "exact frontend response", responseX - 65, top + 125);
        gc.drawLine(left, top + 115, responseX, top + 115);
        gc.drawLine(responseX, top + 115, responseX, top + 95);
        gc.drawLine(responseX, top + 95, left + 2 * cycleWidth, top + 95);
        int textY = top + 165;
        drawLabel(gc, "L = " + latency + " frontend cycles; response annotation = "
                + text(timing, "read_latency_ns_text") + " ns (raw analysis values)", 12, textY);
        drawLabel(gc, "Requested " + text(clock, "port_requested_hz") + " Hz; period "
                + text(clock, "port_period_ns_text") + " ns; ratio " + text(clock, "frequency_ratio_exact")
                + "; cycle-model " + text(clock, "cycle_model_port_hz_text") + " Hz", 12, textY + 22);
        gc.setForeground(color(SWT.COLOR_DARK_BLUE));
        drawLabel(gc, "Exact frontend response only — not backend/MIG completion. Posted writes; analytical only.",
                12, textY + 48);
    }

    private void paintSlots(GC gc, Rectangle area) {
        JsonObject timing = analysis.getAsJsonObject("timing");
        String[] keys = { "maintenance_cycles", "first_data_cycles", "cdc_overhead_cycles", "port_slot_cycles",
                "margin_cycles", "headroom_cycles" };
        String[] labels = { "Maintenance", "First data", "CDC", "Port slots", "Margin", "Headroom" };
        int[] colors = { SWT.COLOR_DARK_BLUE, SWT.COLOR_DARK_CYAN, SWT.COLOR_DARK_GREEN, SWT.COLOR_DARK_YELLOW,
                SWT.COLOR_DARK_MAGENTA, SWT.COLOR_GRAY };
        long total = 0;
        for (String key : keys) {
            total += longValue(timing, key);
        }
        long target = longValue(timing, "target_period_cycles");
        long scale = Math.max(1, Math.max(total, target));
        int x = 15;
        int width = Math.max(100, area.width - 30);
        for (int i = 0; i < keys.length; i++) {
            long cycles = longValue(timing, keys[i]);
            int segment = (int) Math.max(cycles == 0 ? 0 : 3, cycles * (width - 10) / scale);
            gc.setBackground(color(colors[i]));
            gc.fillRectangle(x, 45, segment, 42);
            gc.setForeground(color(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawRectangle(x, 45, segment, 42);
            x += segment;
        }
        gc.setBackground(color(SWT.COLOR_WIDGET_BACKGROUND));
        drawLabel(gc, text(timing, "budget_used_cycles") + " used / " + text(timing, "target_period_cycles")
                + " target; headroom " + text(timing, "headroom_cycles") + "; strict pass "
                + text(timing, "strict_budget_passes"), 15, 15);
        for (int i = 0; i < keys.length; i++) {
            int row = i / 2;
            int column = i % 2;
            int tx = 20 + column * Math.max(300, area.width / 2);
            int ty = 110 + row * 35;
            gc.setBackground(color(colors[i]));
            gc.fillRectangle(tx, ty, 18, 18);
            gc.setForeground(color(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawRectangle(tx, ty, 18, 18);
            drawLabel(gc, labels[i] + " = " + text(timing, keys[i]) + " cycles", tx + 28, ty);
        }
        drawLabel(gc, "Stacked allocation; nominal per-port slot offsets remain in the table below.", 15, 220);
    }

    private void paintMemory(GC gc, Rectangle area) {
        JsonArray ports = ports();
        long maximum = 1;
        for (var item : ports) {
            maximum = Math.max(maximum, longValue(item.getAsJsonObject(), "backend_end_beats"));
        }
        int left = 85;
        int usable = Math.max(250, area.width - left - 25);
        drawLabel(gc, "Backend base/window (64-byte beats); minimum visible window width preserves small ports", 10, 10);
        for (int i = 0; i < ports.size(); i++) {
            JsonObject port = ports.get(i).getAsJsonObject();
            long base = longValue(port, "base_beats");
            long end = longValue(port, "backend_end_beats");
            int y = 45 + i * 22;
            int x = left + (int) (base * usable / maximum);
            int width = end > base ? Math.max(6, (int) ((end - base) * usable / maximum)) : 2;
            gc.setBackground(color(SWT.COLOR_DARK_CYAN));
            gc.fillRectangle(x, y, width, 15);
            gc.setForeground(color(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawRectangle(x, y, width, 15);
            drawLabel(gc, "P" + text(port, "index"), 12, y - 2);
            String label = "base " + text(port, "base_beats") + ", end " + text(port, "backend_end_beats")
                    + ", size " + text(port, "size_lanes") + ", tail " + text(port, "size_tail_lanes")
                    + ", padding " + text(port, "backend_window_padding_lanes") + ", capacity "
                    + text(port, "capacity_words");
            int labelX = Math.max(45, Math.min(x + width + 5, area.width - gc.textExtent(label).x - 8));
            drawLabel(gc, label, labelX, y - 2);
        }
    }

    private void paintBandwidth(GC gc, Rectangle area) {
        JsonObject bandwidth = analysis.getAsJsonObject("bandwidth");
        String[] keys = { "useful_payload_bps", "frontend_payload_bytes_per_second",
                "allocated_backend_bytes_per_second" };
        String[] labels = { "Useful payload (bit/s)", "Frontend payload (byte/s)", "Allocated backend (byte/s)" };
        long maximum = 1;
        for (String key : keys) {
            maximum = Math.max(maximum, longValue(bandwidth, key));
        }
        int barWidth = Math.max(200, area.width - 320);
        for (int i = 0; i < keys.length; i++) {
            long value = longValue(bandwidth, keys[i]);
            int y = 35 + i * 55;
            int width = Math.max(3, (int) (value * barWidth / maximum));
            gc.setBackground(color(i == 2 ? SWT.COLOR_DARK_MAGENTA : SWT.COLOR_DARK_GREEN));
            gc.fillRectangle(20, y, width, 28);
            gc.setForeground(color(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawRectangle(20, y, width, 28);
            drawLabel(gc, labels[i] + " = " + text(bandwidth, keys[i]), 30, y + 5);
        }
        drawLabel(gc, "Backend theoretical = " + text(bandwidth, "backend_theoretical_bytes_per_second")
                + " byte/s; allocated fraction = " + text(bandwidth, "allocated_backend_fraction"), 20, 205);
        gc.setForeground(color(SWT.COLOR_DARK_BLUE));
        drawLabel(gc, "Configured demand, not measured throughput or board qualification.", 20, 228);
    }

    private JsonArray ports() {
        return analysis != null && analysis.has("ports") ? analysis.getAsJsonArray("ports") : new JsonArray();
    }

    private org.eclipse.swt.graphics.Color color(int id) {
        return Display.getCurrent().getSystemColor(id);
    }

    private void drawLabel(GC gc, String text, int x, int y) {
        gc.setBackground(color(SWT.COLOR_WIDGET_BACKGROUND));
        gc.drawText(text, x, y);
    }

    private static String text(JsonObject object, String key) {
        return BackendController.text(object, key);
    }

    private static int integer(JsonObject object, String key) {
        return object.get(key).getAsInt();
    }

    private static long longValue(JsonObject object, String key) {
        return object.get(key).getAsLong();
    }
}
