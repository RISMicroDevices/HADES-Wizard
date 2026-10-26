package cn.rismd.hades.wizard.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import cn.rismd.hades.wizard.core.provider.FixtureProvider;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderException;
import cn.rismd.hades.wizard.core.provider.ProviderLoader;
import cn.rismd.hades.wizard.core.provider.ProviderVersion;

public class ProviderLoaderTest {
    @Test
    public void loadsFixtureMetadataAndStructure() throws Exception {
        ProviderDescriptor provider = FixtureProvider.load();
        assertEquals("cn.rismd.hades.wizard.fixture", provider.id());
        assertEquals(ProviderVersion.parse("1.0.0"), provider.version());
        assertEquals(2, provider.pages().size());
        assertEquals(1, provider.reports().size());
        assertEquals("configurationName", provider.pages().get(0).fields().get(0).id());
    }

    @Test
    public void rejectsMalformedJson() {
        ProviderException error = assertThrows(ProviderException.class,
                () -> new ProviderLoader().load(new StringReader("{not-json")));
        assertTrue(error.getMessage().contains("Malformed"));
    }

    @Test
    public void rejectsMissingIdentity() throws Exception {
        ProviderException error = assertThrows(ProviderException.class,
                () -> load(fixtureJson().replace("  \"id\": \"cn.rismd.hades.wizard.fixture\",\n", "")));
        assertTrue(error.getMessage().contains("id"));
    }

    @Test
    public void rejectsMissingVersion() throws Exception {
        ProviderException error = assertThrows(ProviderException.class,
                () -> load(fixtureJson().replace("  \"version\": \"1.0.0\",\n", "")));
        assertTrue(error.getMessage().contains("version"));
    }

    @Test
    public void rejectsUnsupportedSchemaVersion() throws Exception {
        ProviderException error = assertThrows(ProviderException.class,
                () -> load(fixtureJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")));
        assertTrue(error.getMessage().contains("schema version 2"));
    }

    @Test
    public void rejectsUnsupportedProtocolVersion() throws Exception {
        ProviderException error = assertThrows(ProviderException.class,
                () -> load(fixtureJson().replace("\"protocolVersion\": 1", "\"protocolVersion\": 99")));
        assertTrue(error.getMessage().contains("protocol version 99"));
    }

    @Test
    public void rejectsInvalidSemanticVersion() throws Exception {
        ProviderException error = assertThrows(ProviderException.class,
                () -> load(fixtureJson().replace("\"version\": \"1.0.0\"", "\"version\": \"one\"")));
        assertTrue(error.getMessage().contains("Malformed"));
    }

    private static ProviderDescriptor load(String json) throws ProviderException {
        return new ProviderLoader().load(new StringReader(json));
    }

    private static String fixtureJson() throws Exception {
        try (var input = FixtureProvider.class.getResourceAsStream("/providers/fixture-provider.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
