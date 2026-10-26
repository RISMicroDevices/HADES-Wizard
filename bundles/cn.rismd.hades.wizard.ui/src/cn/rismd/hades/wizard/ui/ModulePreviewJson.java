package cn.rismd.hades.wizard.ui;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import cn.rismd.hades.wizard.modulepreview.ModuleModel;
import cn.rismd.hades.wizard.modulepreview.PortDirection;
import cn.rismd.hades.wizard.modulepreview.PortGroup;
import cn.rismd.hades.wizard.modulepreview.PortSignal;

final class ModulePreviewJson {
    private ModulePreviewJson() {
    }

    static ModuleModel fromJson(JsonObject module) {
        if (module == null) {
            return null;
        }
        try {
            List<PortGroup> groups = new ArrayList<>();
            for (JsonElement item : requiredArray(module, "groups")) {
                JsonObject group = item.getAsJsonObject();
                List<PortSignal> signals = new ArrayList<>();
                for (JsonElement signalItem : requiredArray(group, "signals")) {
                    JsonObject signal = signalItem.getAsJsonObject();
                    signals.add(new PortSignal(
                            requiredText(signal, "name"),
                            direction(requiredText(signal, "direction")),
                            requiredText(signal, "width"),
                            requiredText(signal, "description")));
                }
                groups.add(new PortGroup(
                        requiredText(group, "id"),
                        requiredText(group, "label"),
                        requiredText(group, "clock_domain"),
                        group.get("expanded").getAsBoolean(),
                        List.copyOf(signals)));
            }
            return new ModuleModel(requiredText(module, "name"), List.copyOf(groups));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static com.google.gson.JsonArray requiredArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("Expected module metadata array: " + key);
        }
        return object.getAsJsonArray(key);
    }

    private static String requiredText(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("Expected module metadata string: " + key);
        }
        return object.get(key).getAsString();
    }

    private static PortDirection direction(String value) {
        return switch (value) {
            case "in" -> PortDirection.INPUT;
            case "out" -> PortDirection.OUTPUT;
            default -> throw new IllegalArgumentException("Unknown port direction: " + value);
        };
    }
}
