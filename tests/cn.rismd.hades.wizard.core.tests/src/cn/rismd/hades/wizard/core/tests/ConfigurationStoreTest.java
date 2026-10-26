package cn.rismd.hades.wizard.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

import cn.rismd.hades.wizard.core.json.ConfigurationStore;
import cn.rismd.hades.wizard.core.launch.WizardSession;
import cn.rismd.hades.wizard.core.provider.FixtureProvider;
import cn.rismd.hades.wizard.core.provider.ProviderException;

public class ConfigurationStoreTest {
    @Test
    public void savesAndRestoresFinishedSession() throws Exception {
        var provider = FixtureProvider.load();
        var session = new WizardSession(provider);
        session.setValue("configurationName", "Round trip");
        session.setValue("message", "Saved by a real core test");
        var launch = session.finish();
        assertEquals("Round trip", launch.values().get("configurationName"));

        Path file = Files.createTempDirectory("hades-wizard-test").resolve("configuration.json");
        ConfigurationStore.save(file, session);
        WizardSession restored = ConfigurationStore.load(file, provider);
        assertEquals(WizardSession.State.FINISHED, restored.state());
        assertEquals(session.values(), restored.values());
    }

    @Test
    public void rejectsUnknownFields() throws Exception {
        var provider = FixtureProvider.load();
        assertThrows(ProviderException.class, () -> WizardSession.restore(provider, Map.of("unknown", "value")));
    }

    @Test
    public void cannotSaveAnUnfinishedSession() throws Exception {
        var provider = FixtureProvider.load();
        Path file = Files.createTempDirectory("hades-wizard-test").resolve("unfinished.json");
        assertThrows(ProviderException.class, () -> ConfigurationStore.save(file, new WizardSession(provider)));
    }
}
