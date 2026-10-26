package cn.rismd.hades.wizard.ui;

import java.nio.file.Path;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

public interface ConfigFileChooser {
    Path open(Shell shell);

    Path save(Shell shell, Path current);

    static ConfigFileChooser nativeDialogs() {
        return new ConfigFileChooser() {
            @Override
            public Path open(Shell shell) {
                var dialog = new FileDialog(shell, SWT.OPEN);
                dialog.setText("Open Configuration");
                dialog.setFilterExtensions(new String[] { "*.json", "*.*" });
                String selected = dialog.open();
                return selected == null ? null : Path.of(selected);
            }

            @Override
            public Path save(Shell shell, Path current) {
                var dialog = new FileDialog(shell, SWT.SAVE);
                dialog.setText("Save Configuration");
                dialog.setFileName(current == null ? "configuration.json" : current.getFileName().toString());
                dialog.setFilterExtensions(new String[] { "*.json", "*.*" });
                dialog.setOverwrite(true);
                String selected = dialog.open();
                return selected == null ? null : Path.of(selected);
            }
        };
    }
}
