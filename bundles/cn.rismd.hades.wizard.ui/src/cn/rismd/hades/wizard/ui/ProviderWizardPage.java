package cn.rismd.hades.wizard.ui;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import cn.rismd.hades.wizard.core.launch.WizardSession;
import cn.rismd.hades.wizard.core.provider.FieldDescriptor;
import cn.rismd.hades.wizard.core.provider.FieldType;
import cn.rismd.hades.wizard.core.provider.PageDescriptor;

final class ProviderWizardPage extends WizardPage {
    private final PageDescriptor descriptor;
    private final WizardSession session;

    ProviderWizardPage(PageDescriptor descriptor, WizardSession session) {
        super(descriptor.id());
        this.descriptor = descriptor;
        this.session = session;
        setTitle(descriptor.title());
        setDescription(descriptor.description());
    }

    @Override
    public void createControl(Composite parent) {
        var composite = new Composite(parent, SWT.NONE);
        composite.setLayout(new GridLayout(2, false));
        for (var field : descriptor.fields()) {
            createField(composite, field);
        }
        setControl(composite);
        updateCompletion();
    }

    private void createField(Composite parent, FieldDescriptor field) {
        var label = new Label(parent, SWT.NONE);
        label.setText(field.label());
        label.setToolTipText(field.description());
        var style = field.type() == FieldType.MULTILINE ? SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL
                : SWT.BORDER | SWT.SINGLE;
        var text = new Text(parent, style);
        text.setData("hades.fieldId", field.id());
        text.setToolTipText(field.description());
        text.setMessage(field.description());
        var layoutData = new GridData(SWT.FILL, SWT.FILL, true, field.type() == FieldType.MULTILINE);
        if (field.type() == FieldType.MULTILINE) {
            layoutData.heightHint = 5 * text.getLineHeight();
        }
        text.setLayoutData(layoutData);
        text.addModifyListener(event -> {
            session.setValue(field.id(), text.getText());
            updateCompletion();
        });
        text.setText(session.values().get(field.id()));
    }

    private void updateCompletion() {
        setPageComplete(session.isPageComplete(descriptor));
        getContainer().updateButtons();
    }
}
