package cn.rismd.hades.wizard.ui.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swtbot.swt.finder.SWTBot;
import org.eclipse.swtbot.swt.finder.waits.Conditions;
import org.eclipse.swtbot.swt.finder.widgets.SWTBotShell;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import cn.rismd.hades.wizard.core.launch.WizardSession;
import cn.rismd.hades.wizard.core.provider.FixtureProvider;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.ui.ProviderWizard;

public class ProviderWizardSWTBotTest {
    private Display display;
    private Thread eventThread;

    @Before
    public void startDisplay() throws Exception {
        var ready = new CountDownLatch(1);
        eventThread = new Thread(() -> {
            display = new Display();
            ready.countDown();
            while (!display.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
        }, "HADES-Wizard SWTBot display");
        eventThread.start();
        assertTrue("Display did not start", ready.await(10, TimeUnit.SECONDS));
    }

    @After
    public void disposeDisplay() throws Exception {
        if (display != null && !display.isDisposed()) {
            display.syncExec(display::dispose);
        }
        eventThread.join(10_000);
    }

    @Test
    public void navigatesBackNextAndFinishes() throws Exception {
        var opened = openWizard();
        SWTBotShell shell = opened.bot.shell("Fixture Configuration");
        shell.activate();
        SWTBot bot = shell.bot();
        bot.textWithLabel("Configuration name:").setText("");
        assertFalse(bot.button("Next >").isEnabled());
        bot.textWithLabel("Configuration name:").setText("SWTBot configuration");
        bot.button("Next >").click();
        bot.textWithLabel("Message:").setText("Native UI verification");
        bot.button("< Back").click();
        assertEquals("SWTBot configuration", bot.textWithLabel("Configuration name:").getText());
        bot.button("Next >").click();
        assertEquals("Native UI verification", bot.textWithLabel("Message:").getText());
        bot.button("Finish").click();
        bot.waitUntil(Conditions.shellCloses(shell));

        assertEquals(Window.OK, opened.result.get());
        assertEquals(WizardSession.State.FINISHED, opened.wizard.get().session().state());
        assertEquals("Native UI verification", opened.wizard.get().launchRequest().values().get("message"));
    }

    @Test
    public void cancelDoesNotCreateALaunchRequest() throws Exception {
        var opened = openWizard();
        SWTBotShell shell = opened.bot.shell("Fixture Configuration");
        shell.activate();
        shell.bot().button("Cancel").click();
        shell.bot().waitUntil(Conditions.shellCloses(shell));
        assertEquals(Window.CANCEL, opened.result.get());
        assertEquals(WizardSession.State.CANCELLED, opened.wizard.get().session().state());
        assertNull(opened.wizard.get().launchRequest());
    }

    private OpenedWizard openWizard() throws Exception {
        ProviderDescriptor provider = FixtureProvider.load();
        var result = new AtomicInteger(-1);
        var wizard = new AtomicReference<ProviderWizard>();
        var bot = new SWTBot();
        display.asyncExec(() -> {
            var providerWizard = new ProviderWizard(provider);
            wizard.set(providerWizard);
            result.set(new WizardDialog(null, providerWizard).open());
        });
        return new OpenedWizard(bot, result, wizard);
    }

    private record OpenedWizard(SWTBot bot, AtomicInteger result, AtomicReference<ProviderWizard> wizard) {
    }
}
