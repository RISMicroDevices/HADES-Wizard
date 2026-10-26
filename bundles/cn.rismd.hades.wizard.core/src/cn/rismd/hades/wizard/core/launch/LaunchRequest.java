package cn.rismd.hades.wizard.core.launch;

import java.util.Map;
import java.util.Objects;

import cn.rismd.hades.wizard.core.provider.ProviderVersion;

public record LaunchRequest(String providerId, ProviderVersion providerVersion, Map<String, String> values) {
    public LaunchRequest {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider id is required");
        }
        Objects.requireNonNull(providerVersion, "providerVersion");
        values = Map.copyOf(values);
    }
}
