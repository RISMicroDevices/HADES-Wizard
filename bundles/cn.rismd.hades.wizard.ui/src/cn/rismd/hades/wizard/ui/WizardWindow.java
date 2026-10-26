package cn.rismd.hades.wizard.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

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
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import cn.rismd.hades.wizard.core.json.ConfigurationStore;
import cn.rismd.hades.wizard.core.launch.WizardSession;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderException;

public final class WizardWindow extends ApplicationWindow {
    private final ProviderDescriptor provider;
    private WizardSession session;
    private Path currentFile;
    private boolean dirty;
    private Text summary;

    public WizardWindow(ProviderDescriptor provider) {
        super(null);
        this.provider = provider;
        session = new WizardSession(provider);
        addMenuBar();
        addStatusLine();
    }

    @Override
    protected void configureShell(org.eclipse.swt.widgets.Shell shell) {
        super.configureShell(shell);
        shell.setText("HADES-Wizard");
        shell.setMinimumSize(520, 320);
        var images = new ArrayList<Image>();
        for (String name : new String[] { "wizard-16.png", "wizard-32.png", "wizard-48.png" }) {
            var resource = WizardWindow.class.getResource("/icons/" + name);
            if (resource != null) {
                images.add(ImageDescriptor.createFromURL(resource).createImage());
            }
        }
        shell.setImages(images.toArray(Image[]::new));
        shell.addDisposeListener(event -> images.forEach(Image::dispose));
    }

    @Override
    protected Point getInitialSize() {
        return new Point(680, 420);
    }

    @Override
    protected Control createContents(Composite parent) {
        var composite = new Composite(parent, SWT.NONE);
        composite.setLayout(new GridLayout(1, false));
        var heading = new Label(composite, SWT.NONE);
        heading.setText(provider.display().name());
        var description = new Label(composite, SWT.WRAP);
        description.setText(provider.display().description());
        description.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        var label = new Label(composite, SWT.NONE);
        label.setText("Configuration &summary:");
        summary = new Text(composite, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        updateSummary();
        return composite;
    }

    @Override
    protected MenuManager createMenuManager() {
        var menu = new MenuManager("&File");
        menu.add(new Action("&New Configuration...", SWT.MOD1 + 'n') {
            @Override
            public void run() {
                newConfiguration();
            }
        });
        menu.add(new Action("&Open...", SWT.MOD1 + 'o') {
            @Override
            public void run() {
                openConfiguration();
            }
        });
        menu.add(new Separator());
        menu.add(new Action("&Save", SWT.MOD1 + 's') {
            @Override
            public void run() {
                saveConfiguration(false);
            }
        });
        menu.add(new Action("Save &As...", SWT.MOD1 + SWT.SHIFT + 's') {
            @Override
            public void run() {
                saveConfiguration(true);
            }
        });
        menu.add(new Separator());
        menu.add(new Action("E&xit") {
            @Override
            public void run() {
                close();
            }
        });
        return menu;
    }

    private void newConfiguration() {
        if (!confirmDiscard()) {
            return;
        }
        var wizard = new ProviderWizard(provider);
        var dialog = new WizardDialog(getShell(), wizard);
        if (dialog.open() == WizardDialog.OK) {
            session = wizard.session();
            currentFile = null;
            dirty = true;
            updateSummary();
            updateTitle();
        }
    }

    private void openConfiguration() {
        if (!confirmDiscard()) {
            return;
        }
        var dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("Open Configuration");
        dialog.setFilterExtensions(new String[] { "*.json", "*.*" });
        String selected = dialog.open();
        if (selected == null) {
            return;
        }
        try {
            session = ConfigurationStore.load(Path.of(selected), provider);
            currentFile = Path.of(selected);
            dirty = false;
            updateSummary();
            updateTitle();
        } catch (IOException | ProviderException | IllegalArgumentException e) {
            showError("Open Configuration", "Could not open the configuration", e);
        }
    }

    private void saveConfiguration(boolean alwaysChooseFile) {
        if (session.state() != WizardSession.State.FINISHED) {
            MessageDialog.openInformation(getShell(), "Save Configuration",
                    "Finish a configuration wizard before saving.");
            return;
        }
        Path destination = currentFile;
        if (alwaysChooseFile || destination == null) {
            var dialog = new FileDialog(getShell(), SWT.SAVE);
            dialog.setText("Save Configuration");
            dialog.setFileName(currentFile == null ? "configuration.json" : currentFile.getFileName().toString());
            dialog.setFilterExtensions(new String[] { "*.json", "*.*" });
            dialog.setOverwrite(true);
            String selected = dialog.open();
            if (selected == null) {
                return;
            }
            if (!selected.toLowerCase().contains(".")) {
                selected += ".json";
            }
            destination = Path.of(selected);
        }
        try {
            ConfigurationStore.save(destination, session);
            currentFile = destination;
            dirty = false;
            updateTitle();
            setStatus("Saved " + destination);
        } catch (IOException | ProviderException e) {
            showError("Save Configuration", "Could not save the configuration", e);
        }
    }

    private boolean confirmDiscard() {
        return !dirty || MessageDialog.openQuestion(getShell(), "Unsaved Configuration",
                "Discard the unsaved configuration?");
    }

    @Override
    public boolean close() {
        return confirmDiscard() && super.close();
    }

    private void updateSummary() {
        if (summary == null || summary.isDisposed()) {
            return;
        }
        if (session.state() != WizardSession.State.FINISHED) {
            summary.setText("No completed configuration. Choose File > New Configuration to begin.");
            return;
        }
        var text = new StringBuilder(provider.display().name()).append(System.lineSeparator())
                .append("Provider: ").append(provider.id()).append(' ').append(provider.version())
                .append(System.lineSeparator()).append(System.lineSeparator());
        for (var page : provider.pages()) {
            for (var field : page.fields()) {
                text.append(field.label().replace("&", "")).append(' ')
                        .append(session.values().get(field.id())).append(System.lineSeparator());
            }
        }
        summary.setText(text.toString());
    }

    private void updateTitle() {
        if (getShell() == null || getShell().isDisposed()) {
            return;
        }
        getShell().setText("HADES-Wizard" + (currentFile == null ? "" : " - " + currentFile.getFileName())
                + (dirty ? " *" : ""));
    }

    private void showError(String title, String message, Exception e) {
        MessageDialog.openError(getShell(), title, message + ":" + System.lineSeparator() + e.getMessage());
    }
}
