package cn.rismd.hades.wizard.core.launch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import cn.rismd.hades.wizard.core.provider.PageDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderException;

public final class WizardSession {
    public enum State {
        EDITING,
        FINISHED,
        CANCELLED
    }

    private final ProviderDescriptor provider;
    private final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private State state = State.EDITING;

    public WizardSession(ProviderDescriptor provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        for (var page : provider.pages()) {
            for (var field : page.fields()) {
                values.put(field.id(), field.defaultValue() == null ? "" : field.defaultValue());
            }
        }
    }

    public static WizardSession restore(ProviderDescriptor provider, Map<String, String> restoredValues)
            throws ProviderException {
        validateValues(provider, restoredValues);
        var session = new WizardSession(provider);
        session.values.clear();
        session.values.putAll(restoredValues);
        session.state = State.FINISHED;
        return session;
    }

    public ProviderDescriptor provider() {
        return provider;
    }

    public State state() {
        return state;
    }

    public Map<String, String> values() {
        return Collections.unmodifiableMap(values);
    }

    public void setValue(String fieldId, String value) {
        if (state != State.EDITING) {
            throw new IllegalStateException("Only an editing session can be changed");
        }
        if (!values.containsKey(fieldId)) {
            throw new IllegalArgumentException("Unknown field '" + fieldId + "'");
        }
        values.put(fieldId, Objects.requireNonNull(value, "value"));
    }

    public boolean isPageComplete(PageDescriptor page) {
        return page.fields().stream().filter(field -> field.required())
                .allMatch(field -> !values.get(field.id()).isBlank());
    }

    public LaunchRequest finish() throws ProviderException {
        if (state != State.EDITING) {
            throw new IllegalStateException("Only an editing session can be finished");
        }
        validateValues(provider, values);
        state = State.FINISHED;
        return new LaunchRequest(provider.id(), provider.version(), values);
    }

    public void cancel() {
        if (state == State.EDITING) {
            state = State.CANCELLED;
        }
    }

    public static void validateValues(ProviderDescriptor provider, Map<String, String> candidate)
            throws ProviderException {
        var known = new LinkedHashMap<String, Boolean>();
        for (var page : provider.pages()) {
            for (var field : page.fields()) {
                known.put(field.id(), field.required());
            }
        }
        for (String id : candidate.keySet()) {
            if (!known.containsKey(id)) {
                throw new ProviderException("Configuration contains unknown field '" + id + "'");
            }
        }
        for (var entry : known.entrySet()) {
            String value = candidate.get(entry.getKey());
            if (value == null || (entry.getValue() && value.isBlank())) {
                throw new ProviderException("Configuration field '" + entry.getKey() + "' is required");
            }
        }
    }
}
