package com.hmdm.launcher.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.launcher.json.ServerConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class MqttConfigurationTest {
    @Test public void downloadedConfigurationUsesCanonicalPropertyAndOldServersRemainCompatible() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ServerConfig oldServer = json.readValue("{\"pushOptions\":\"mqttAlarm\"}", ServerConfig.class);
        assertNull(oldServer.getMqttUrl());
        ServerConfig newServer = json.readValue("{\"mqttUrl\":\"ssl://broker.example\"}", ServerConfig.class);
        assertEquals("ssl://broker.example", newServer.getMqttUrl());
        ServerConfig restored = json.readValue(json.writeValueAsString(newServer), ServerConfig.class);
        assertEquals(newServer.getMqttUrl(), restored.getMqttUrl());
        assertNull(json.readValue("{}", ServerConfig.class).getMqttUrl());
    }
}
