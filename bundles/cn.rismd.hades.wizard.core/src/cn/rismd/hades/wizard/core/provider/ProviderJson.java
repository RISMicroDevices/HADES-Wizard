package cn.rismd.hades.wizard.core.provider;

import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

public final class ProviderJson {
    private ProviderJson() {
    }

    public static Gson gson() {
        return new GsonBuilder().registerTypeAdapter(ProviderVersion.class, new VersionAdapter()).create();
    }

    public static Gson prettyGson() {
        return new GsonBuilder().registerTypeAdapter(ProviderVersion.class, new VersionAdapter()).setPrettyPrinting()
                .create();
    }

    private static final class VersionAdapter extends TypeAdapter<ProviderVersion> {
        @Override
        public void write(JsonWriter out, ProviderVersion value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public ProviderVersion read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return ProviderVersion.parse(in.nextString());
        }
    }
}
