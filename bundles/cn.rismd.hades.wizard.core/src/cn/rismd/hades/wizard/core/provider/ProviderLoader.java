package cn.rismd.hades.wizard.core.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

public final class ProviderLoader {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final int SUPPORTED_PROTOCOL_VERSION = 1;
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+");
    private static final Pattern LOCAL_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

    public ProviderDescriptor load(InputStream input) throws ProviderException {
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return load(reader);
        } catch (IOException e) {
            throw new ProviderException("Could not read provider JSON", e);
        }
    }

    public ProviderDescriptor load(Reader reader) throws ProviderException {
        final ProviderDescriptor provider;
        try {
            var json = JsonParser.parseReader(reader);
            if (!json.isJsonObject()) {
                throw new ProviderException("Provider JSON must be an object");
            }
            provider = ProviderJson.gson().fromJson(json, ProviderDescriptor.class);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new ProviderException("Malformed provider JSON: " + e.getMessage(), e);
        }
        if (provider == null) {
            throw new ProviderException("Provider JSON must not be null");
        }
        validate(provider);
        return provider;
    }

    private static void validate(ProviderDescriptor provider) throws ProviderException {
        if (provider.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new ProviderException("Unsupported provider schema version " + provider.schemaVersion()
                    + "; supported version is " + SUPPORTED_SCHEMA_VERSION);
        }
        if (provider.protocolVersion() != SUPPORTED_PROTOCOL_VERSION) {
            throw new ProviderException("Unsupported provider protocol version " + provider.protocolVersion()
                    + "; supported version is " + SUPPORTED_PROTOCOL_VERSION);
        }
        if (provider.id() == null || !PROVIDER_ID.matcher(provider.id()).matches()) {
            throw new ProviderException("Provider id is required and must be a reverse-domain identifier");
        }
        if (provider.version() == null) {
            throw new ProviderException("Provider version is required");
        }
        if (provider.backend() != null && (blank(provider.backend().entryPoint())
                || java.nio.file.Path.of(provider.backend().entryPoint()).isAbsolute())) {
            throw new ProviderException("Backend entryPoint must be a non-empty relative path");
        }
        if (provider.backend() != null && blank(provider.vlnv())) {
            throw new ProviderException("Provider vlnv is required when a backend is declared");
        }
        if (provider.display() == null || blank(provider.display().name())) {
            throw new ProviderException("Provider display.name is required");
        }
        if (provider.pages() == null || provider.pages().isEmpty()) {
            throw new ProviderException("Provider must declare at least one page");
        }
        Set<String> pageIds = new HashSet<>();
        Set<String> fieldIds = new HashSet<>();
        for (var page : provider.pages()) {
            validateLocalId(page == null ? null : page.id(), "page");
            if (!pageIds.add(page.id())) {
                throw new ProviderException("Duplicate page id '" + page.id() + "'");
            }
            if (blank(page.title()) || page.fields() == null || page.fields().isEmpty()) {
                throw new ProviderException("Page '" + page.id() + "' requires a title and at least one field");
            }
            for (var field : page.fields()) {
                validateLocalId(field == null ? null : field.id(), "field");
                if (!fieldIds.add(field.id())) {
                    throw new ProviderException("Duplicate field id '" + field.id() + "'");
                }
                if (blank(field.label()) || field.type() == null) {
                    throw new ProviderException("Field '" + field.id() + "' requires a label and supported type");
                }
            }
        }
        Set<String> reportIds = new HashSet<>();
        for (var report : provider.reports()) {
            validateLocalId(report == null ? null : report.id(), "report");
            if (!reportIds.add(report.id()) || blank(report.title())) {
                throw new ProviderException("Reports require unique ids and non-empty titles");
            }
        }
    }

    private static void validateLocalId(String id, String kind) throws ProviderException {
        if (id == null || !LOCAL_ID.matcher(id).matches()) {
            throw new ProviderException("Invalid or missing " + kind + " id '" + id + "'");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
