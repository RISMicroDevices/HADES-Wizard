package cn.rismd.hades.wizard.core.json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonParseException;

import cn.rismd.hades.wizard.core.launch.ConfigurationDocument;
import cn.rismd.hades.wizard.core.launch.WizardSession;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderException;
import cn.rismd.hades.wizard.core.provider.ProviderJson;

public final class ConfigurationStore {
    private static final int DOCUMENT_SCHEMA_VERSION = 1;

    private ConfigurationStore() {
    }

    public static void save(Path path, WizardSession session) throws IOException, ProviderException {
        if (session.state() != WizardSession.State.FINISHED) {
            throw new ProviderException("Only a finished configuration can be saved");
        }
        var provider = session.provider();
        var document = new ConfigurationDocument(DOCUMENT_SCHEMA_VERSION, provider.id(), provider.version(),
                session.values());
        Files.writeString(path, ProviderJson.prettyGson().toJson(document) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    public static WizardSession load(Path path, ProviderDescriptor provider) throws IOException, ProviderException {
        final ConfigurationDocument document;
        try {
            document = ProviderJson.gson().fromJson(Files.readString(path, StandardCharsets.UTF_8),
                    ConfigurationDocument.class);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new ProviderException("Malformed configuration JSON: " + e.getMessage(), e);
        }
        if (document == null || document.schemaVersion() != DOCUMENT_SCHEMA_VERSION) {
            throw new ProviderException("Unsupported or missing configuration schema version");
        }
        if (!provider.id().equals(document.providerId()) || !provider.version().equals(document.providerVersion())) {
            throw new ProviderException("Configuration does not match provider " + provider.id() + " "
                    + provider.version());
        }
        if (document.values() == null) {
            throw new ProviderException("Configuration values are required");
        }
        return WizardSession.restore(provider, document.values());
    }
}
