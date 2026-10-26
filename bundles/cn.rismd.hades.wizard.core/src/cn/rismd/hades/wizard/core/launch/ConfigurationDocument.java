package cn.rismd.hades.wizard.core.launch;

import java.util.Map;

import cn.rismd.hades.wizard.core.provider.ProviderVersion;

public record ConfigurationDocument(int schemaVersion, String providerId, ProviderVersion providerVersion,
        Map<String, String> values) {
    public ConfigurationDocument {
        values = values == null ? null : Map.copyOf(values);
    }
}
