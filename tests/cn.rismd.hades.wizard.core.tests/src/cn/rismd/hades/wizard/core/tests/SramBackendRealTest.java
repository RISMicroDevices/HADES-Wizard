package cn.rismd.hades.wizard.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cn.rismd.hades.wizard.core.backend.BackendService;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderJson;
import cn.rismd.hades.wizard.core.provider.ProviderLoader;

public class SramBackendRealTest {
    private static Path descriptorPath;

    @BeforeClass
    public static void requireRealBackend() {
        String configured = System.getProperty("hades.sram.descriptor");
        Assume.assumeTrue(configured != null && !configured.isBlank());
        descriptorPath = Path.of(configured);
    }

    @Test
    public void descriptorDeclaresFivePagesAndHandshakeSucceeds() throws Exception {
        ProviderDescriptor provider = load();
        assertEquals("cn.rismd.hades.sram", provider.id());
        assertEquals(5, provider.pages().size());
        try (BackendService service = service(provider)) {
            assertEquals("rismd.cn:hades:hades_sram:1.1", service.schema().get("vlnv").getAsString());
            assertEquals(1, service.schema().get("protocol_version").getAsInt());
        }
    }

    @Test
    public void realAnalyzeAllocatePreviewSaveGenerateAndParity() throws Exception {
        ProviderDescriptor provider = load();
        Path temporary = Files.createTempDirectory("hades-real-backend");
        try (BackendService service = service(provider)) {
            JsonObject defaults = service.schema().getAsJsonObject("defaults");
            JsonObject analysis = service.analyze(defaults);
            assertTrue(BackendControllerSupport.valid(analysis));
            assertEquals(1, analysis.getAsJsonObject("config").get("port_count").getAsInt());
            assertEquals(32, analysis.getAsJsonObject("config").getAsJsonArray("ports").get(0).getAsJsonObject()
                    .get("width").getAsInt());
            assertEquals(4194304, analysis.getAsJsonArray("depths").get(0).getAsInt());
            assertTrue(analysis.get("module").isJsonObject());
            assertFalse(analysis.getAsJsonObject("config").has("full_copy"));

            JsonArray depths = new JsonArray();
            depths.add(17);
            JsonObject allocated = service.allocate(analysis.getAsJsonObject("config"), depths);
            assertEquals(17, allocated.getAsJsonObject("analysis").getAsJsonArray("ports").get(0).getAsJsonObject()
                    .get("capacity_words").getAsInt());
            JsonObject config = allocated.getAsJsonObject("config");

            JsonObject preview = service.preview(config);
            assertTrue(preview.has("artifacts") && !preview.get("artifacts").isJsonNull());
            JsonObject artifacts = preview.getAsJsonObject("artifacts");
            assertTrue(artifacts.has("static_rtl.f"));
            assertTrue(artifacts.has("logical/hades_sram_top.sv"));
            assertTrue(artifacts.has("constraints/sram0_strict.xdc"));
            Path output = temporary.resolve("generated");
            JsonObject generated = service.generate(config, output, false, false);
            assertEquals("generated", generated.get("outcome").getAsString());
            assertEquals(0, generated.get("status").getAsInt());
            assertArtifactParity(artifacts, output);

            JsonObject reducedPreview = service.preview(config, false);
            JsonObject reducedArtifacts = reducedPreview.getAsJsonObject("artifacts");
            assertFalse(reducedArtifacts.has("static_rtl.f"));
            assertFalse(reducedArtifacts.has("logical/hades_sram_top.sv"));
            assertTrue(reducedArtifacts.has("wrapper.f"));
            assertTrue(reducedArtifacts.has("constraints/sram0_strict.xdc"));
            Path reducedOutput = temporary.resolve("reduced");
            JsonObject reduced = service.generate(config, reducedOutput, false, false, false);
            assertEquals("generated", reduced.get("outcome").getAsString());
            assertArtifactParity(reducedArtifacts, reducedOutput);

            Path saved = temporary.resolve("saved.json");
            assertTrue(service.save(config, saved, false).get("saved").getAsBoolean());
            assertFalse(service.save(config, saved, false).get("saved").getAsBoolean());
            assertTrue(service.save(config, saved, true).get("saved").getAsBoolean());
            JsonObject reopened = service.analyzeText(Files.readString(saved));
            assertTrue(BackendControllerSupport.valid(reopened));
        }
    }

    private static void assertArtifactParity(JsonObject artifacts, Path output) throws Exception {
        Map<String, String> actual = new HashMap<>();
        try (var files = Files.walk(output)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                actual.put(output.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        assertEquals(artifacts.keySet(), actual.keySet());
        for (String name : artifacts.keySet()) {
            assertEquals(name, artifacts.get(name).getAsString(), actual.get(name));
        }
    }

    @Test
    public void semanticInvalidRetainsDepthsAndStructuralInvalidHasNoConfig() throws Exception {
        ProviderDescriptor provider = load();
        try (BackendService service = service(provider)) {
            JsonObject config = service.schema().getAsJsonObject("defaults");
            config.addProperty("port_clock_hz", 123);
            JsonObject result = service.analyze(config);
            assertFalse(result.get("valid").getAsBoolean());
            assertTrue(result.get("analysis").isJsonNull());
            assertNotNull(result.get("depths"));
            assertTrue(result.get("module").isJsonObject());
            JsonObject structural = service.analyzeText("{\"port_count\":1,\"port_count\":2}");
            assertFalse(structural.get("valid").getAsBoolean());
            assertTrue(structural.get("config").isJsonNull());
            assertTrue(structural.get("module").isJsonNull());
        }
    }

    private static ProviderDescriptor load() throws Exception {
        try (var input = Files.newInputStream(descriptorPath)) {
            return new ProviderLoader().load(input);
        }
    }

    private static BackendService service(ProviderDescriptor provider) throws Exception {
        return BackendService.launch(provider, descriptorPath, System.getProperty("hades.sram.python", "python3"),
                ProviderJson.gson(), Duration.ofSeconds(30));
    }

    private static final class BackendControllerSupport {
        private BackendControllerSupport() {
        }

        static boolean valid(JsonObject result) {
            return result.get("valid").getAsBoolean();
        }
    }
}
