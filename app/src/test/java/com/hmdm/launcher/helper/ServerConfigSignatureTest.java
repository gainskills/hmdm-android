package com.hmdm.launcher.helper;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ServerConfigSignatureTest {
    private static final String SECRET = "test-secret";
    private static final String DATA = "{\"mqttUrl\":\"ssl://destination.example:8883\"}";
    private static final String RESPONSE = "{\"status\":\"OK\",\"message\":null,\"data\":" + DATA + "}";

    private static String signature(String data) {
        return CryptoHelper.getSHA1String(SECRET + data.replaceAll("\\s", ""));
    }

    @Test public void acceptsAuthenticDestinationData() {
        assertEquals(DATA, CryptoHelper.getVerifiedServerData(RESPONSE, signature(DATA), SECRET));
    }

    @Test public void preservesExistingCaseInsensitiveSignatureAndWhitespaceRules() {
        String spacedData = "{\"mqttUrl\": \"ssl://destination.example:8883\"}";
        String response = "{\"status\":\"OK\",\"data\":" + spacedData + "}";
        assertEquals(spacedData, CryptoHelper.getVerifiedServerData(response, signature(DATA).toLowerCase(), SECRET));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsUnsignedDestinationData() {
        CryptoHelper.getVerifiedServerData(RESPONSE, null, SECRET);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsTamperedMqttEndpoint() {
        CryptoHelper.getVerifiedServerData(RESPONSE.replace("destination.example", "attacker.example"), signature(DATA), SECRET);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsWrongSecret() {
        CryptoHelper.getVerifiedServerData(RESPONSE, signature(DATA), "wrong-secret");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsMissingData() {
        CryptoHelper.getVerifiedServerData("{\"status\":\"OK\"}", signature(DATA), SECRET);
    }
}
