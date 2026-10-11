package com.hmdm.launcher.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** An actual MQTT exchange interrupted between PUBREC and PUBREL, then resumed. */
public class MqttPersistenceTest {
    @Test public void incomingQos2SurvivesClientRecreation() throws Exception { verifyRestart(false); }
    @Test public void incomingQos2SurvivesLegacyImport() throws Exception { verifyRestart(true); }

    private void verifyRestart(boolean legacyImport) throws Exception {
        File directory = Files.createTempDirectory("mqtt-protocol-state").toFile();
        ServerSocket broker = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        ExecutorService server = Executors.newSingleThreadExecutor();
        CountDownLatch receivedPubrec = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        Future<?> brokerWork = server.submit(() -> {
            try {
                try (Socket socket = broker.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    assertEquals(0x10, readPacket(input)[0]);
                    output.write(new byte[]{0x20, 2, 0, 0}); output.flush();
                    int[] subscribe = readPacket(input);
                    assertEquals(0x82, subscribe[0]);
                    output.write(new byte[]{(byte)0x90, 3, (byte)subscribe[1], (byte)subscribe[2], 2});
                    byte[] topic = "device".getBytes(StandardCharsets.UTF_8);
                    byte[] payload = "pending command".getBytes(StandardCharsets.UTF_8);
                    output.write(0x34); output.write(2 + topic.length + 2 + payload.length);
                    output.write(0); output.write(topic.length); output.write(topic);
                    output.write(0); output.write(42); output.write(payload); output.flush();
                    assertEquals(0x50, readPacket(input)[0]); // PUBREC: receiver must persist before this.
                    receivedPubrec.countDown();
                    while (input.read() != -1) { /* Wait for the first client to retire. */ }
                }
                try (Socket socket = broker.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    assertEquals(0x10, readPacket(input)[0]);
                    output.write(new byte[]{0x20, 2, 1, 0}); // Existing persistent session.
                    output.write(new byte[]{0x62, 2, 0, 42}); output.flush();
                    assertEquals(0x70, readPacket(input)[0]); // PUBCOMP after restored delivery.
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        MqttAsyncClient first = null, second = null;
        try {
            String endpoint = "tcp://127.0.0.1:" + broker.getLocalPort();
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(false); options.setAutomaticReconnect(false); options.setConnectionTimeout(3);
            File firstDirectory = legacyImport ? directory : MqttSessionPersistence.directory(new File(directory, "private"), null, null, endpoint, "device");
            first = new MqttAsyncClient(endpoint, "device", new MqttDefaultFilePersistence(firstDirectory.getAbsolutePath()));
            first.setCallback(callback(new CountDownLatch(1)));
            first.connect(options).waitForCompletion(5000);
            first.subscribe("device", 2).waitForCompletion(5000);
            assertTrue("Broker did not receive PUBREC", receivedPubrec.await(5, TimeUnit.SECONDS));
            first.disconnectForcibly(0, 1000, false); first.close(true); first = null;
            second = new MqttAsyncClient(endpoint, "device", new MqttDefaultFilePersistence(MqttSessionPersistence.directory(new File(directory, "private"), directory, endpoint, endpoint, "device").getAbsolutePath()));
            second.setCallback(callback(delivered));
            second.connect(options).waitForCompletion(5000);
            assertTrue("Persisted incoming payload was lost", delivered.await(5, TimeUnit.SECONDS));
            brokerWork.get(5, TimeUnit.SECONDS);
        } finally {
            if (first != null) { first.disconnectForcibly(0, 1000, false); first.close(true); }
            if (second != null) { second.disconnectForcibly(0, 1000, false); second.close(true); }
            broker.close(); server.shutdownNow();
            delete(directory);
        }
    }

    private static MqttCallback callback(CountDownLatch delivered) {
        return new MqttCallback() {
            public void connectionLost(Throwable t) {}
            public void deliveryComplete(IMqttDeliveryToken t) {}
            public void messageArrived(String topic, MqttMessage message) {
                assertEquals("device", topic);
                assertEquals("pending command", new String(message.getPayload(), StandardCharsets.UTF_8));
                delivered.countDown();
            }
        };
    }
    private static int[] readPacket(InputStream input) throws IOException {
        int header = input.read();
        if (header < 0) throw new EOFException();
        int size = 0, multiplier = 1, next;
        do {
            next = input.read(); if (next < 0) throw new EOFException();
            size += (next & 127) * multiplier; multiplier *= 128;
        } while ((next & 128) != 0);
        int[] packet = new int[size + 1]; packet[0] = header;
        for (int i = 1; i <= size; i++) { packet[i] = input.read(); if (packet[i] < 0) throw new EOFException(); }
        return packet;
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
