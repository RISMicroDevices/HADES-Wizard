package cn.rismd.hades.wizard.modulepreview;

import java.util.List;
import java.util.Objects;

public record PortGroup(String id, String label, String clockDomain, boolean initiallyExpanded,
        List<PortSignal> signals) {
    public PortGroup {
        requireText(id, "Group id");
        requireText(label, "Group label");
        Objects.requireNonNull(clockDomain, "clockDomain");
        signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
    }

    private static void requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
