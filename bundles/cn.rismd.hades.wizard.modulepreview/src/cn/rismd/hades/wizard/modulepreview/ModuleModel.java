package cn.rismd.hades.wizard.modulepreview;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ModuleModel(String name, List<PortGroup> groups) {
    public ModuleModel {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Module name must not be blank");
        }
        groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        var ids = new HashSet<String>();
        for (PortGroup group : groups) {
            if (!ids.add(group.id())) {
                throw new IllegalArgumentException("Duplicate group id: " + group.id());
            }
        }
    }
}
