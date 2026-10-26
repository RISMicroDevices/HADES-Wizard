package cn.rismd.hades.wizard.app;

import java.io.IOException;
import java.nio.file.Files;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.ApplicationWindow;
import org.eclipse.swt.widgets.Display;

import cn.rismd.hades.wizard.core.provider.FixtureProvider;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderException;
import cn.rismd.hades.wizard.core.provider.ProviderLoader;
import cn.rismd.hades.wizard.ui.BackendWindow;
import cn.rismd.hades.wizard.ui.WizardWindow;

public final class WizardApplication implements IApplication {
    private ApplicationWindow window;

    @Override
    public Object start(IApplicationContext context) {
        var display = new Display();
        try {
            ApplicationOptions options = ApplicationOptions.parse(context.getArguments());
            var providerPath = options.resolveProvider();
            if (providerPath == null) {
                window = new WizardWindow(FixtureProvider.load());
            } else {
                ProviderDescriptor provider;
                try (var input = Files.newInputStream(providerPath)) {
                    provider = new ProviderLoader().load(input);
                }
                window = new BackendWindow(provider, providerPath, options.python(), options.output());
            }
            window.open();
            if (window instanceof BackendWindow backendWindow) {
                if (options.config() != null) {
                    backendWindow.openConfiguration(options.config());
                } else {
                    display.asyncExec(backendWindow::newConfiguration);
                }
            }
            var shell = window.getShell();
            while (!shell.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
            return EXIT_OK;
        } catch (ProviderException | IOException | IllegalArgumentException e) {
            MessageDialog.openError(null, "HADES-Wizard", "Cannot initialize the application: " + e.getMessage());
            return Integer.valueOf(1);
        } finally {
            display.dispose();
        }
    }

    @Override
    public void stop() {
        if (window == null) {
            return;
        }
        var display = Display.getDefault();
        display.asyncExec(() -> {
            if (window.getShell() != null && !window.getShell().isDisposed()) {
                window.getShell().close();
            }
        });
    }
}
