package cn.rismd.hades.wizard.modulepreview;

import java.util.Objects;

public record PortSignal(String name, PortDirection direction, String width, String description) {
    public PortSignal {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Signal name must not be blank");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(width, "width");
        if (width.isBlank()) {
            throw new IllegalArgumentException("Signal width must not be blank");
        }
        Objects.requireNonNull(description, "description");
    }

    public PortSignal(String name, PortDirection direction, int width, String description) {
        this(name, direction, positiveWidth(width), description);
    }

    private static String positiveWidth(int width) {
        if (width <= 0) {
            throw new IllegalArgumentException("Signal width must be positive");
        }
        return Integer.toString(width);
    }
}
