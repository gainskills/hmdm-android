package com.hmdm.launcher.mqtt;

import org.junit.Test;
import static org.junit.Assert.*;

public class MqttEndpointTest {
    @Test public void oldServerUsesBuildDefaultsAndCurrentHmdmHost() {
        assertEquals("tcp://old.example:31000", MqttEndpoint.resolve(null, "https://old.example/path", 31000, false));
        assertEquals("ssl://new.example:31000", MqttEndpoint.resolve("  ", "http://new.example", 31000, true));
    }
    @Test public void explicitEndpointControlsTransportAndPort() {
        assertEquals("ssl://broker.example:8883", MqttEndpoint.resolve("mqtts://BROKER.example", "https://hmdm", 31000, false));
        assertEquals("tcp://broker.example:1883", MqttEndpoint.resolve("mqtt://broker.example", "https://hmdm", 31000, true));
        assertEquals("ssl://broker.example:31001", MqttEndpoint.resolve("ssl://broker.example:31001", "https://hmdm", 31000, false));
        assertEquals("tcp://[::1]:1883", MqttEndpoint.resolve("tcp://[::1]", "https://hmdm", 31000, false));
    }
    @Test public void hostnamesWithUnderscoresAreSupported() {
        assertEquals("tcp://mqtt_broker:1883", MqttEndpoint.resolve("tcp://mqtt_broker", "https://hmdm", 31000, false));
        assertEquals("ssl://mqtt_broker:8883", MqttEndpoint.resolve("ssl://mqtt_broker", "https://hmdm", 31000, false));
        assertEquals("tcp://mqtt_broker:31000", MqttEndpoint.resolve("tcp://mqtt_broker:31000", "https://hmdm", 31000, false));
        assertEquals("tcp://hmdm_server:31000", MqttEndpoint.resolve(null, "http://hmdm_server:8080", 31000, false));
    }
    @Test public void explicitInvalidUrlsNeverUseFallback() {
        for (String invalid : new String[]{"ws://host", "https://host", "tcp://", "tcp://host:0", "tcp://host:65536", "tcp://user:secret@host", "tcp://user:secret@host_name", "tcp://host/path", "tcp://host?password=secret", "tcp://host#fragment", "tcp://host:abc"}) {
            try { MqttEndpoint.resolve(invalid, "https://valid.example", 31000, false); fail(invalid); }
            catch (IllegalArgumentException expected) {}
        }
    }
}
