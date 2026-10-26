package cn.rismd.hades.wizard.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.Test;

import com.google.gson.JsonObject;

import cn.rismd.hades.wizard.core.backend.BackendException;
import cn.rismd.hades.wizard.core.backend.BackendService;
import cn.rismd.hades.wizard.core.provider.ProviderDescriptor;
import cn.rismd.hades.wizard.core.provider.ProviderJson;
import cn.rismd.hades.wizard.core.provider.ProviderLoader;

public class BackendServiceTest {
    @Test
    public void assignsSynchronizedMonotonicRequestIds() throws Exception {
        Path directory = Files.createTempDirectory("hades-backend-ids");
        ProviderDescriptor provider = provider(directory, "normal");
        try (BackendService service = launch(provider, directory)) {
            assertEquals(2, service.call("first", new JsonObject()).get("seen_id").getAsInt());
            assertEquals(3, service.call("second", new JsonObject()).get("seen_id").getAsInt());
        }
    }

    @Test
    public void rejectsMalformedResponseAndClosesOwnedProcess() throws Exception {
        Path directory = Files.createTempDirectory("hades-backend-malformed");
        ProviderDescriptor provider = provider(directory, "malformed");
        try (BackendService service = launch(provider, directory)) {
            BackendException error = assertThrows(BackendException.class, () -> service.analyze(new JsonObject()));
            assertEquals("E_RESPONSE_MALFORMED", error.code());
            assertFalse(service.isAlive());
        }
    }

    @Test
    public void rejectsLateResponseIds() throws Exception {
        Path directory = Files.createTempDirectory("hades-backend-late");
        ProviderDescriptor provider = provider(directory, "late");
        try (BackendService service = launch(provider, directory)) {
            BackendException error = assertThrows(BackendException.class, () -> service.analyze(new JsonObject()));
            assertEquals("E_RESPONSE_ID", error.code());
            assertFalse(service.isAlive());
        }
    }

    @Test
    public void timeoutCancelsAndCleansUpOnlyOwnedChild() throws Exception {
        Path directory = Files.createTempDirectory("hades-backend-timeout");
        ProviderDescriptor provider = provider(directory, "timeout");
        BackendService service = BackendService.launch(provider, directory.resolve("provider.json"), "python3",
                ProviderJson.gson(), Duration.ofSeconds(2));
        BackendException error = assertThrows(BackendException.class, () -> service.analyze(new JsonObject()));
        assertEquals("E_TIMEOUT", error.code());
        assertFalse(service.isAlive());
        service.close();
        service.close();
    }

    @Test
    public void rejectsHandshakeVersionMismatch() throws Exception {
        Path directory = Files.createTempDirectory("hades-backend-handshake");
        ProviderDescriptor provider = provider(directory, "version_mismatch");
        BackendException error = assertThrows(BackendException.class, () -> launch(provider, directory));
        assertEquals("E_HANDSHAKE", error.code());
    }

    @Test
    public void boundsStderrDrain() throws Exception {
        Path directory = Files.createTempDirectory("hades-backend-stderr");
        ProviderDescriptor provider = provider(directory, "stderr");
        try (BackendService service = launch(provider, directory)) {
            assertTrue(service.stderr().length() <= 64 * 1024);
        }
    }

    static ProviderDescriptor provider(Path directory, String mode) throws Exception {
        Files.writeString(directory.resolve("fake.py"), fakeBackend(mode));
        String json = """
                {"schemaVersion":1,"protocolVersion":1,"id":"cn.rismd.hades.sram","version":"1.0.0",
                "vlnv":"rismd.cn:hades:hades_sram:1.1","display":{"name":"Fake"},
                "backend":{"entryPoint":"fake.py"},
                "pages":[{"id":"p","title":"P","kind":"review","fields":[{"id":"x","label":"X","type":"STRING","required":false}]}]}
                """;
        ProviderDescriptor provider = new ProviderLoader().load(new StringReader(json));
        Files.writeString(directory.resolve("provider.json"), json);
        return provider;
    }

    private static BackendService launch(ProviderDescriptor provider, Path directory) throws BackendException {
        return BackendService.launch(provider, directory.resolve("provider.json"), "python3", ProviderJson.gson(),
                Duration.ofSeconds(5));
    }

    private static String fakeBackend(String mode) {
        return """
                import json,sys,time
                MODE = '%s'
                if MODE == 'stderr':
                    sys.stderr.write('x' * 100000)
                    sys.stderr.flush()
                def response(i, result=None):
                    if MODE == 'malformed' and i != 1:
                        sys.stdout.write('{bad\\n');sys.stdout.flush();return
                    if MODE == 'late' and i != 1:
                        i += 100
                    if result is None:
                        result={'seen_id':i}
                    sys.stdout.write(json.dumps({'protocol_version':1,'id':i,'ok':True,'result':result},separators=(',',':'))+'\\n')
                    sys.stdout.flush()
                for line in sys.stdin:
                    request=json.loads(line)
                    if request['operation']=='schema':
                        version='2.0.0' if MODE=='version_mismatch' else '1.0.0'
                        response(request['id'],{'name':'fake','version':version,'protocol_version':1,'schema_version':1,'vlnv':'rismd.cn:hades:hades_sram:1.1','defaults':{},'fields':{'global':{},'port':{}}})
                    else:
                        if MODE=='timeout':
                            time.sleep(30)
                        response(request['id'])
                """.formatted(mode);
    }
}
