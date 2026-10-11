package com.hmdm.launcher.mqtt;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MqttSessionPersistenceTest {
    @Test public void endpointsWithCollidingPahoNamesHaveSeparateStateAndImportsAreNotRepeated() throws Exception {
        File root = Files.createTempDirectory("mqtt-session-test").toFile();
        try {
            File a = MqttSessionPersistence.directory(root, null, null, "tcp://a.b:1883", "device");
            File b = MqttSessionPersistence.directory(root, null, null, "tcp://ab:1883", "device");
            assertNotEquals(a, b);
            File bogusLegacy = new File(root, "device-tcpab1883");
            assertTrue(bogusLegacy.mkdir());
            Files.write(new File(bogusLegacy, "r-42.msg").toPath(), new byte[]{1});
            assertEquals(a, MqttSessionPersistence.directory(root, root, "tcp://a.b:1883", "tcp://a.b:1883", "device"));
            assertFalse(new File(a, "device-tcpab1883/r-42.msg").exists());
        } finally { delete(root); }
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
