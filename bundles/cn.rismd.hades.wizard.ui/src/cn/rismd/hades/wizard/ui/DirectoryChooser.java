package cn.rismd.hades.wizard.ui;

import java.nio.file.Path;

import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Shell;

@FunctionalInterface
public interface DirectoryChooser {
    Path choose(Shell shell, String title, Path initial);

    static DirectoryChooser nativeDialogs() {
        return (shell, title, initial) -> {
            var dialog = new DirectoryDialog(shell);
            dialog.setText(title);
            if (initial != null) {
                dialog.setFilterPath(initial.toString());
            }
            String selected = dialog.open();
            return selected == null ? null : Path.of(selected);
        };
    }
}
