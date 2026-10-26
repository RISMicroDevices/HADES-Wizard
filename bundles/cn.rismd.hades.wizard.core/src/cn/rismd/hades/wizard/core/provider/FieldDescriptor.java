package cn.rismd.hades.wizard.core.provider;

import java.util.List;

public record FieldDescriptor(String id, String label, String description, FieldType type, boolean required,
        String defaultValue, List<String> choices) {
    public FieldDescriptor {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
