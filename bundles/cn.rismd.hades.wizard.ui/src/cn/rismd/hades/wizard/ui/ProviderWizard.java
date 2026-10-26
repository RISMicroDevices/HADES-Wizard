package cn.rismd.hades.wizard.ui;

import org.eclipse.jface.wizard.Wizard;

import cn.rismd.hades.wizard.core.launch.LaunchRequest;
import cn.rismd.hades.wizard.core.launch.WizardSession;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderException;

public final class ProviderWizard extends Wizard {
    private final WizardSession session;
    private LaunchRequest launchRequest;

    public ProviderWizard(ProviderDescriptor provider) {
        session = new WizardSession(provider);
        setWindowTitle(provider.display().name());
        setNeedsProgressMonitor(false);
        setHelpAvailable(false);
    }

    @Override
    public void addPages() {
        for (var page : session.provider().pages()) {
            addPage(new ProviderWizardPage(page, session));
        }
    }

    @Override
    public boolean performFinish() {
        try {
            launchRequest = session.finish();
            return true;
        } catch (ProviderException e) {
            if (getContainer().getCurrentPage() instanceof org.eclipse.jface.wizard.WizardPage page) {
                page.setErrorMessage(e.getMessage());
            }
            return false;
        }
    }

    @Override
    public boolean performCancel() {
        session.cancel();
        return true;
    }

    public WizardSession session() {
        return session;
    }

    public LaunchRequest launchRequest() {
        return launchRequest;
    }
}
