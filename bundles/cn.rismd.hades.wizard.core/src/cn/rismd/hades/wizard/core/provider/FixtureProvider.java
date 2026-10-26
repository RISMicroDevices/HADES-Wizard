package cn.rismd.hades.wizard.core.provider;

import java.io.InputStream;

public final class FixtureProvider {
    private FixtureProvider() {
    }

    public static ProviderDescriptor load() throws ProviderException {
        InputStream input = FixtureProvider.class.getResourceAsStream("/providers/fixture-provider.json");
        if (input == null) {
            throw new ProviderException("Built-in fixture provider resource is missing");
        }
        return new ProviderLoader().load(input);
    }
}
