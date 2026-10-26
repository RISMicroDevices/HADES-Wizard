package cn.rismd.hades.wizard.modulepreview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.events.TreeAdapter;
import org.eclipse.swt.events.TreeEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

public final class ModulePortPreview extends Composite {
    public static final double MIN_ZOOM = 0.05;
    public static final double MAX_ZOOM = 4.0;

    private static final double ZOOM_FACTOR = 1.25;
    private static final int MODULE_X = 24;
    private static final int MODULE_Y = 24;
    private static final int MODULE_WIDTH = 680;
    private static final int MODULE_MARGIN = 24;
    private static final int MODULE_HEADER = 70;
    private static final int MODULE_BOTTOM = 20;
    private static final int GROUP_INSET = 20;
    private static final int GROUP_HEADER = 54;
    private static final int GROUP_GAP = 12;
    private static final int SIGNAL_HEIGHT = 24;
    private static final int EXPAND_TARGET_SIZE = 20;

    private final Group contentGroup;
    private final Label moduleNameLabel;
    private final Label statusLabel;
    private final Label zoomLabel;
    private final Button zoomInButton;
    private final Button zoomOutButton;
    private final Button fitButton;
    private final Button resetButton;
    private final TabFolder tabs;
    private final Canvas canvas;
    private final Tree tree;
    private final Map<String, Boolean> expansionOverrides = new HashMap<>();

    private ModuleModel model;
    private long revision = -1;
    private long lastPaintRevision = -1;
    private int paintCount;
    private String lastPaintError;
    private List<GroupNode> groupNodes = List.of();
    private ModelRect moduleBounds;
    private int contentWidth;
    private int contentHeight;
    private double zoom = 1.0;
    private double panX;
    private double panY;
    private boolean syncingTree;
    private boolean dragging;
    private boolean dragMoved;
    private int dragStartX;
    private int dragStartY;
    private double dragStartPanX;
    private double dragStartPanY;
    private String pressedGroupId;

    public ModulePortPreview(Composite parent, int style) {
        super(parent, style);
        setLayout(new GridLayout(1, false));

        contentGroup = new Group(this, SWT.NONE);
        contentGroup.setText("Module Port Preview");
        contentGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        contentGroup.setLayout(new GridLayout(1, false));

        moduleNameLabel = new Label(contentGroup, SWT.NONE);
        moduleNameLabel.setText("No module loaded");
        moduleNameLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        configureAccessible(moduleNameLabel, "Module name", "Name of the current module");

        statusLabel = new Label(contentGroup, SWT.NONE);
        statusLabel.setText("No module loaded.");
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        configureAccessible(statusLabel, "Module preview status", "Current model, loading, or validation status");

        var toolbar = new Composite(contentGroup, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var toolbarLayout = new GridLayout(5, false);
        toolbarLayout.marginWidth = 0;
        toolbarLayout.marginHeight = 0;
        toolbar.setLayout(toolbarLayout);

        zoomInButton = button(toolbar, "Zoom In", "Increase diagram zoom", this::zoomIn);
        zoomOutButton = button(toolbar, "Zoom Out", "Decrease diagram zoom", this::zoomOut);
        fitButton = button(toolbar, "Fit", "Fit the complete module diagram in the canvas", this::fit);
        resetButton = button(toolbar, "Reset", "Reset diagram zoom to 100 percent and pan to the origin", this::reset);
        zoomLabel = new Label(toolbar, SWT.NONE);
        var zoomData = new GridData(SWT.RIGHT, SWT.CENTER, true, false);
        zoomData.widthHint = 64;
        zoomLabel.setLayoutData(zoomData);
        configureAccessible(zoomLabel, "Zoom level", "Current diagram zoom percentage");

        tabs = new TabFolder(contentGroup, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        configureAccessible(tabs, "Module preview views", "Diagram and accessible signal tree views");

        var diagramPage = new Composite(tabs, SWT.NONE);
        diagramPage.setLayout(new FillLayout());
        canvas = new Canvas(diagramPage, SWT.DOUBLE_BUFFERED);
        canvas.setToolTipText("Module diagram. Drag to pan; Ctrl+mouse wheel or plus/minus to zoom.");
        var diagramTab = new TabItem(tabs, SWT.NONE);
        diagramTab.setText("Diagram");
        diagramTab.setControl(diagramPage);

        tree = new Tree(tabs, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
        tree.setToolTipText("Exact module groups and signal values");
        String[] columns = { "Signal / Group", "Direction", "Width", "Clock Domain" };
        int[] widths = { 260, 110, 100, 180 };
        for (int i = 0; i < columns.length; i++) {
            var column = new TreeColumn(tree, SWT.NONE);
            column.setText(columns[i]);
            column.setWidth(widths[i]);
        }
        var treeTab = new TabItem(tabs, SWT.NONE);
        treeTab.setText("Signal Tree");
        treeTab.setControl(tree);

        configureCanvasAccessible();
        configureAccessible(tree, "Module signal tree",
                "Expandable port groups with exact signal direction, width, and clock domain values");
        installCanvasListeners();
        installTreeListener();
        updateZoomControls();
    }

    public void setModel(ModuleModel model, long revision) {
        checkWidget();
        this.model = model;
        this.revision = revision;
        if (model == null) {
            moduleNameLabel.setText("No module loaded");
            setStatus("No module loaded.");
        } else {
            moduleNameLabel.setText(model.name());
            setStatus(model.groups().isEmpty() ? "No port groups in module."
                    : model.groups().size() + " port group" + (model.groups().size() == 1 ? "" : "s") + " loaded.");
        }
        rebuildTree();
        rebuildDiagram();
        canvas.redraw();
    }

    public ModuleModel getModel() {
        checkWidget();
        return model;
    }

    public long getRevision() {
        checkWidget();
        return revision;
    }

    public void setStatus(String status) {
        checkWidget();
        String value = status == null ? "" : status;
        statusLabel.setText(value);
        statusLabel.setToolTipText(value);
    }

    public void setGroupExpanded(String groupId, boolean expanded) {
        checkWidget();
        Objects.requireNonNull(groupId, "groupId");
        findGroup(groupId);
        expansionOverrides.put(groupId, expanded);
        TreeItem item = findTreeItem(groupId);
        if (item != null) {
            syncingTree = true;
            try {
                item.setExpanded(expanded);
                tree.showItem(item);
            } finally {
                syncingTree = false;
            }
        }
        rebuildDiagram();
        canvas.redraw();
    }

    public boolean isGroupExpanded(String groupId) {
        checkWidget();
        Objects.requireNonNull(groupId, "groupId");
        PortGroup group = findGroup(groupId);
        Boolean override = expansionOverrides.get(groupId);
        return override == null ? group.initiallyExpanded() : override;
    }

    public void zoomIn() {
        checkWidget();
        zoomAtCenter(zoom * ZOOM_FACTOR);
    }

    public void zoomOut() {
        checkWidget();
        zoomAtCenter(zoom / ZOOM_FACTOR);
    }

    public void panBy(double deltaX, double deltaY) {
        checkWidget();
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY)) {
            throw new IllegalArgumentException("Pan deltas must be finite");
        }
        panX += deltaX;
        panY += deltaY;
        canvas.redraw();
    }

    public void fit() {
        checkWidget();
        if (moduleBounds == null) {
            return;
        }
        Rectangle area = canvas.getClientArea();
        if (area.width <= 0 || area.height <= 0) {
            return;
        }
        double horizontal = Math.max(1, area.width - 16) / (double) contentWidth;
        double vertical = Math.max(1, area.height - 16) / (double) contentHeight;
        double fittedZoom = clampZoom(Math.min(horizontal, vertical));
        applyView(fittedZoom, (area.width - contentWidth * fittedZoom) / 2.0,
                (area.height - contentHeight * fittedZoom) / 2.0);
    }

    public void reset() {
        checkWidget();
        applyView(1.0, 0.0, 0.0);
    }

    public DiagramPoint modelToScreen(double modelX, double modelY) {
        checkWidget();
        return new DiagramPoint(modelX * zoom + panX, modelY * zoom + panY);
    }

    public DiagramPoint screenToModel(double screenX, double screenY) {
        checkWidget();
        return new DiagramPoint((screenX - panX) / zoom, (screenY - panY) / zoom);
    }

    public Rectangle getGroupExpandHitTarget(String groupId) {
        checkWidget();
        Objects.requireNonNull(groupId, "groupId");
        for (GroupNode node : groupNodes) {
            if (node.group().id().equals(groupId)) {
                return toScreen(node.expandTarget());
            }
        }
        return null;
    }

    public Group getContentGroup() {
        checkWidget();
        return contentGroup;
    }

    public Label getModuleNameLabel() {
        checkWidget();
        return moduleNameLabel;
    }

    public Label getStatusLabel() {
        checkWidget();
        return statusLabel;
    }

    public Label getZoomLabel() {
        checkWidget();
        return zoomLabel;
    }

    public Button getZoomInButton() {
        checkWidget();
        return zoomInButton;
    }

    public Button getZoomOutButton() {
        checkWidget();
        return zoomOutButton;
    }

    public Button getFitButton() {
        checkWidget();
        return fitButton;
    }

    public Button getResetButton() {
        checkWidget();
        return resetButton;
    }

    public TabFolder getTabs() {
        checkWidget();
        return tabs;
    }

    public Canvas getCanvas() {
        checkWidget();
        return canvas;
    }

    public Tree getTree() {
        checkWidget();
        return tree;
    }

    public double getZoom() {
        checkWidget();
        return zoom;
    }

    public int getZoomPercent() {
        checkWidget();
        return (int) Math.round(zoom * 100.0);
    }

    public double getPanX() {
        checkWidget();
        return panX;
    }

    public double getPanY() {
        checkWidget();
        return panY;
    }

    public long getLastPaintRevision() {
        checkWidget();
        return lastPaintRevision;
    }

    public String getLastPaintError() {
        checkWidget();
        return lastPaintError;
    }

    public int getPaintCount() {
        checkWidget();
        return paintCount;
    }

    private Button button(Composite parent, String text, String description, Runnable action) {
        var button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setToolTipText(description);
        button.addSelectionListener(SelectionListener.widgetSelectedAdapter(event -> action.run()));
        configureAccessible(button, text, description);
        return button;
    }

    private PortGroup findGroup(String groupId) {
        if (model != null) {
            for (PortGroup group : model.groups()) {
                if (group.id().equals(groupId)) {
                    return group;
                }
            }
        }
        throw new IllegalArgumentException("Unknown group id: " + groupId);
    }

    private TreeItem findTreeItem(String groupId) {
        for (TreeItem item : tree.getItems()) {
            if (groupId.equals(item.getData("modulepreview.groupId"))) {
                return item;
            }
        }
        return null;
    }

    private void rebuildTree() {
        syncingTree = true;
        tree.setRedraw(false);
        try {
            tree.removeAll();
            if (model != null) {
                for (PortGroup group : model.groups()) {
                    var groupItem = new TreeItem(tree, SWT.NONE);
                    groupItem.setText(new String[] { group.label(), "", "", group.clockDomain() });
                    groupItem.setData("modulepreview.groupId", group.id());
                    groupItem.setData("modulepreview.group", group);
                    for (PortSignal signal : group.signals()) {
                        var signalItem = new TreeItem(groupItem, SWT.NONE);
                        signalItem.setText(new String[] { signal.name(), directionText(signal.direction()), signal.width(),
                                group.clockDomain() });
                        signalItem.setData("modulepreview.signal", signal);
                    }
                    Boolean override = expansionOverrides.get(group.id());
                    groupItem.setExpanded(override == null ? group.initiallyExpanded() : override);
                }
            }
        } finally {
            tree.setRedraw(true);
            syncingTree = false;
        }
    }

    private void rebuildDiagram() {
        if (model == null) {
            groupNodes = List.of();
            moduleBounds = null;
            contentWidth = 0;
            contentHeight = 0;
            return;
        }
        var nodes = new ArrayList<GroupNode>(model.groups().size());
        int y = MODULE_Y + MODULE_HEADER;
        int bottom = y;
        int groupWidth = MODULE_WIDTH - 2 * GROUP_INSET;
        for (PortGroup group : model.groups()) {
            boolean expanded = isGroupExpanded(group.id());
            int rows = expanded ? Math.max(1, group.signals().size()) : 0;
            int height = GROUP_HEADER + rows * SIGNAL_HEIGHT;
            var bounds = new ModelRect(MODULE_X + GROUP_INSET, y, groupWidth, height);
            var target = new ModelRect(bounds.x() + 10, bounds.y() + 10, EXPAND_TARGET_SIZE, EXPAND_TARGET_SIZE);
            nodes.add(new GroupNode(group, bounds, target, bounds.y() + GROUP_HEADER));
            bottom = y + height;
            y = bottom + GROUP_GAP;
        }
        groupNodes = List.copyOf(nodes);
        moduleBounds = new ModelRect(MODULE_X, MODULE_Y, MODULE_WIDTH, bottom + MODULE_BOTTOM - MODULE_Y);
        contentWidth = MODULE_X + MODULE_WIDTH + MODULE_MARGIN;
        contentHeight = MODULE_Y + moduleBounds.height() + MODULE_MARGIN;
    }

    private void installTreeListener() {
        tree.addTreeListener(new TreeAdapter() {
            @Override
            public void treeExpanded(TreeEvent event) {
                treeExpansionChanged(event, true);
            }

            @Override
            public void treeCollapsed(TreeEvent event) {
                treeExpansionChanged(event, false);
            }
        });
    }

    private void treeExpansionChanged(TreeEvent event, boolean expanded) {
        if (!syncingTree && event.item instanceof TreeItem item) {
            Object id = item.getData("modulepreview.groupId");
            if (id instanceof String groupId) {
                expansionOverrides.put(groupId, expanded);
                rebuildDiagram();
                canvas.redraw();
            }
        }
    }

    private void installCanvasListeners() {
        canvas.addPaintListener(event -> paint(event.gc));
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent event) {
                if (event.button != 1) {
                    return;
                }
                canvas.setFocus();
                dragging = true;
                dragMoved = false;
                dragStartX = event.x;
                dragStartY = event.y;
                dragStartPanX = panX;
                dragStartPanY = panY;
                pressedGroupId = hitGroup(event.x, event.y);
                canvas.setCapture(true);
            }

            @Override
            public void mouseUp(MouseEvent event) {
                if (event.button != 1 || !dragging) {
                    return;
                }
                String clickedGroup = !dragMoved ? pressedGroupId : null;
                dragging = false;
                pressedGroupId = null;
                canvas.setCapture(false);
                if (clickedGroup != null && clickedGroup.equals(hitGroup(event.x, event.y))) {
                    setGroupExpanded(clickedGroup, !isGroupExpanded(clickedGroup));
                }
            }
        });
        canvas.addMouseMoveListener(event -> {
            if (!dragging || (event.stateMask & SWT.BUTTON1) == 0) {
                return;
            }
            int deltaX = event.x - dragStartX;
            int deltaY = event.y - dragStartY;
            dragMoved |= Math.abs(deltaX) > 3 || Math.abs(deltaY) > 3;
            panX = dragStartPanX + deltaX;
            panY = dragStartPanY + deltaY;
            canvas.redraw();
        });
        canvas.addMouseWheelListener(event -> {
            if ((event.stateMask & SWT.CTRL) != 0 && event.count != 0) {
                setZoomAt(event.count > 0 ? zoom * ZOOM_FACTOR : zoom / ZOOM_FACTOR, event.x, event.y);
            } else if (event.count != 0) {
                panBy(0, event.count * 60.0);
            }
        });
        canvas.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.character == '+' || event.character == '=' || event.keyCode == SWT.KEYPAD_ADD) {
                    zoomIn();
                    event.doit = false;
                } else if (event.character == '-' || event.character == '_' || event.keyCode == SWT.KEYPAD_SUBTRACT) {
                    zoomOut();
                    event.doit = false;
                }
            }
        });
    }

    private String hitGroup(int x, int y) {
        for (GroupNode node : groupNodes) {
            if (toScreen(node.expandTarget()).contains(x, y)) {
                return node.group().id();
            }
        }
        return null;
    }

    private void zoomAtCenter(double newZoom) {
        Rectangle area = canvas.getClientArea();
        setZoomAt(newZoom, area.width / 2, area.height / 2);
    }

    private void setZoomAt(double requestedZoom, double anchorX, double anchorY) {
        double newZoom = clampZoom(requestedZoom);
        if (Double.compare(newZoom, zoom) == 0) {
            return;
        }
        double modelX = (anchorX - panX) / zoom;
        double modelY = (anchorY - panY) / zoom;
        applyView(newZoom, anchorX - modelX * newZoom, anchorY - modelY * newZoom);
    }

    private void applyView(double newZoom, double newPanX, double newPanY) {
        zoom = clampZoom(newZoom);
        panX = newPanX;
        panY = newPanY;
        updateZoomControls();
        canvas.redraw();
    }

    private void updateZoomControls() {
        zoomLabel.setText(getZoomPercent() + "%");
        zoomLabel.setToolTipText("Diagram zoom: " + getZoomPercent() + "%");
        zoomInButton.setEnabled(zoom < MAX_ZOOM);
        zoomOutButton.setEnabled(zoom > MIN_ZOOM);
    }

    private static double clampZoom(double value) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value));
    }

    private void paint(GC gc) {
        lastPaintError = null;
        try {
            Rectangle area = canvas.getClientArea();
            gc.setBackground(canvas.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
            gc.fillRectangle(area);
            gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
            if (model == null) {
                String message = statusLabel.getText().isBlank() ? "No module loaded." : statusLabel.getText();
                gc.drawText(elided(gc, message, Math.max(0, area.width - 24)), 12, 12, true);
            } else {
                paintModule(gc, area);
            }
        } catch (RuntimeException exception) {
            lastPaintError = exception.toString();
            try {
                gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_RED));
                gc.drawText("Diagram rendering error; use the Signal Tree for exact values.", 12, 12, true);
            } catch (RuntimeException fallbackException) {
                lastPaintError += "; fallback rendering failed: " + fallbackException;
            }
        } finally {
            paintCount++;
            lastPaintRevision = revision;
        }
    }

    private void paintModule(GC gc, Rectangle clip) {
        Rectangle module = toScreen(moduleBounds);
        if (!intersects(module, clip)) {
            return;
        }
        gc.setBackground(canvas.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(module);
        gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawRectangle(module);
        if (zoom >= 0.45) {
            drawWorldText(gc, model.name(), moduleBounds.x() + 18, moduleBounds.y() + 10, MODULE_WIDTH - 36);
        }
        if (zoom >= 0.7) {
            drawWorldText(gc, model.groups().size() + " port groups", moduleBounds.x() + 18, moduleBounds.y() + 34,
                    MODULE_WIDTH - 36);
        }
        for (GroupNode node : groupNodes) {
            paintGroup(gc, clip, node);
        }
    }

    private void paintGroup(GC gc, Rectangle clip, GroupNode node) {
        Rectangle bounds = toScreen(node.bounds());
        if (!intersects(bounds, clip)) {
            return;
        }
        gc.setBackground(canvas.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        gc.fillRectangle(bounds);
        gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawRectangle(bounds);
        paintExpandTarget(gc, node);

        ModelRect box = node.bounds();
        if (zoom >= 0.45) {
            drawWorldText(gc, node.group().label(), box.x() + 40, box.y() + 6, box.width() - 52);
        }
        if (zoom >= 0.7) {
            String domain = node.group().clockDomain().isBlank() ? "unspecified" : node.group().clockDomain();
            drawWorldText(gc, "Clock: " + domain, box.x() + 40, box.y() + 29, box.width() - 52);
        }
        if (!isGroupExpanded(node.group().id()) || zoom < 0.75) {
            return;
        }
        if (node.group().signals().isEmpty()) {
            drawWorldText(gc, "No signals", box.x() + 18, node.signalY(), box.width() - 36);
            return;
        }
        for (int i = 0; i < node.group().signals().size(); i++) {
            int rowY = node.signalY() + i * SIGNAL_HEIGHT;
            double screenY = screenY(rowY);
            if (screenY + SIGNAL_HEIGHT * zoom < clip.y || screenY > clip.y + clip.height) {
                continue;
            }
            PortSignal signal = node.group().signals().get(i);
            drawWorldText(gc, signal.name(), box.x() + 18, rowY, 320);
            drawWorldText(gc, directionText(signal.direction()), box.x() + 350, rowY, 130);
            drawWorldText(gc, "W=" + signal.width(), box.x() + 500, rowY, 120);
        }
    }

    private void paintExpandTarget(GC gc, GroupNode node) {
        Rectangle target = toScreen(node.expandTarget());
        gc.setBackground(canvas.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(target);
        gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawRectangle(target);
        int centerX = target.x + target.width / 2;
        int centerY = target.y + target.height / 2;
        int radius = Math.max(1, Math.min(5, target.width / 4));
        gc.drawLine(centerX - radius, centerY, centerX + radius, centerY);
        if (!isGroupExpanded(node.group().id())) {
            gc.drawLine(centerX, centerY - radius, centerX, centerY + radius);
        }
    }

    private void drawWorldText(GC gc, String text, int modelX, int modelY, int modelWidth) {
        int available = (int) Math.max(0, Math.round(modelWidth * zoom));
        String visible = elided(gc, text, available);
        if (!visible.isEmpty()) {
            gc.drawText(visible, screenX(modelX), screenY(modelY), true);
        }
    }

    private static String elided(GC gc, String text, int available) {
        if (available <= 0 || text.isEmpty()) {
            return "";
        }
        if (gc.textExtent(text).x <= available) {
            return text;
        }
        String suffix = "...";
        if (gc.textExtent(suffix).x > available) {
            return "";
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (gc.textExtent(text.substring(0, middle) + suffix).x <= available) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        if (low > 0 && Character.isHighSurrogate(text.charAt(low - 1))) {
            low--;
        }
        return text.substring(0, low) + suffix;
    }

    private Rectangle toScreen(ModelRect rectangle) {
        return new Rectangle(screenX(rectangle.x()), screenY(rectangle.y()),
                Math.max(1, scaled(rectangle.width())), Math.max(1, scaled(rectangle.height())));
    }

    private int screenX(double modelX) {
        return bounded(Math.round(modelX * zoom + panX));
    }

    private int screenY(double modelY) {
        return bounded(Math.round(modelY * zoom + panY));
    }

    private int scaled(int modelSize) {
        return bounded(Math.round(modelSize * zoom));
    }

    private static int bounded(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private static boolean intersects(Rectangle first, Rectangle second) {
        return first.x < second.x + second.width && first.x + first.width > second.x
                && first.y < second.y + second.height && first.y + first.height > second.y;
    }

    private static String directionText(PortDirection direction) {
        return direction == PortDirection.INPUT ? "INPUT" : "OUTPUT";
    }

    private void configureCanvasAccessible() {
        canvas.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override
            public void getName(AccessibleEvent event) {
                event.result = "Module port diagram";
            }

            @Override
            public void getDescription(AccessibleEvent event) {
                String name = model == null ? "No module loaded" : model.name();
                event.result = name + ". Drag to pan; Ctrl+mouse wheel or plus/minus to zoom. "
                        + "Use group plus and minus targets to expand or collapse signals; exact values are in Signal Tree.";
            }
        });
    }

    private static void configureAccessible(Control control, String name, String description) {
        control.getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override
            public void getName(AccessibleEvent event) {
                event.result = name;
            }

            @Override
            public void getDescription(AccessibleEvent event) {
                event.result = description;
            }
        });
    }

    public record DiagramPoint(double x, double y) {
    }

    private record ModelRect(int x, int y, int width, int height) {
    }

    private record GroupNode(PortGroup group, ModelRect bounds, ModelRect expandTarget, int signalY) {
    }
}
