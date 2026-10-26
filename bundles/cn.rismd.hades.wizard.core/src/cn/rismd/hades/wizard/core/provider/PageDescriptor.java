package cn.rismd.hades.wizard.core.provider;

import java.util.List;

public record PageDescriptor(String id, String title, String description, String kind, List<FieldDescriptor> fields) {
    public PageDescriptor {
        fields = fields == null ? null : List.copyOf(fields);
    }
}
