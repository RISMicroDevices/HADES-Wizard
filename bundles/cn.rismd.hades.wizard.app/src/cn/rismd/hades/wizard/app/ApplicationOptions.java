package cn.rismd.hades.wizard.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.core.runtime.Platform;

import cn.rismd.hades.wizard.core.provider.ProviderException;

record ApplicationOptions(Path provider, Path config, Path output, String python, boolean fixture) {
    static ApplicationOptions parse(Map<String, Object> arguments) throws ProviderException {
        Object value = arguments.get("application.args");
        String[] args = value instanceof String[] array ? array : new String[0];
        Path provider = null;
        Path config = null;
        Path output = null;
        String python = null;
        boolean fixture = false;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--fixture")) {
                fixture = true;
                continue;
            }
            if (!ListSupport.options(option) || i + 1 >= args.length) {
                throw new ProviderException("Unknown or incomplete application option: " + option);
            }
            String argument = args[++i];
            switch (option) {
                case "--provider" -> provider = Path.of(argument);
                case "--config" -> config = Path.of(argument);
                case "--output" -> output = Path.of(argument);
                case "--python" -> python = argument;
                default -> throw new ProviderException("Unknown application option: " + option);
            }
        }
        return new ApplicationOptions(provider, config, output, python, fixture);
    }

    Path resolveProvider() throws ProviderException {
        if (fixture && provider != null) {
            throw new ProviderException("--fixture cannot be combined with --provider");
        }
        Path selected = provider;
        if (selected == null && !fixture) {
            try {
                var url = Platform.getInstallLocation().getURL();
                Path candidate = Path.of(url.toURI()).resolve("provider/provider.json");
                if (Files.isRegularFile(candidate)) {
                    selected = candidate;
                }
            } catch (Exception e) {
                throw new ProviderException("Cannot resolve installed provider location", e);
            }
        }
        if (selected != null) {
            if (Files.isDirectory(selected)) {
                selected = selected.resolve("hades_sram_wizard.json");
            }
            if (!Files.isRegularFile(selected)) {
                throw new ProviderException("Provider descriptor does not exist: " + selected);
            }
            return selected.toAbsolutePath().normalize();
        }
        return null;
    }

    private static final class ListSupport {
        private ListSupport() {
        }

        static boolean options(String option) {
            return option.equals("--provider") || option.equals("--config") || option.equals("--output")
                    || option.equals("--python");
        }
    }
}
