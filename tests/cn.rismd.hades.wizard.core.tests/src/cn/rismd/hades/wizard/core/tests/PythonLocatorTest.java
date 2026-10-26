package cn.rismd.hades.wizard.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.Test;

import cn.rismd.hades.wizard.core.backend.BackendException;
import cn.rismd.hades.wizard.core.backend.PythonLocator;

public class PythonLocatorTest {
    @Test
    public void findsRealPython311OrNewer() throws Exception {
        PythonLocator.PythonCommand command = new PythonLocator().locate("python3");
        assertEquals(List.of("python3"), command.prefix());
    }

    @Test
    public void preferenceIsFirstAndWindowsPyHasArgumentList() {
        var locator = new PythonLocator();
        assertEquals(List.of("custom-python"), locator.candidates("custom-python", true).get(0).prefix());
        assertEquals(List.of("py", "-3"), locator.candidates(null, true).get(2).prefix());
    }

    @Test
    public void rejectsOldPythonWithoutFallbackWhenExplicit() throws Exception {
        Path directory = Files.createTempDirectory("hades-python-old");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path executable = directory.resolve(windows ? "old-python.cmd" : "old-python");
        Files.writeString(executable, windows ? "@echo Python 3.10.12\r\n" : "#!/bin/sh\necho 'Python 3.10.12'\n");
        if (!windows) {
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        }
        BackendException error = assertThrows(BackendException.class,
                () -> new PythonLocator().locate(executable.toString()));
        assertEquals("E_PYTHON_VERSION", error.code());
    }

    @Test
    public void explicitMissingExecutableFailsClearly() {
        BackendException error = assertThrows(BackendException.class,
                () -> new PythonLocator().locate("/definitely/missing/python"));
        assertTrue(error.getMessage().contains("Cannot execute"));
    }
}
