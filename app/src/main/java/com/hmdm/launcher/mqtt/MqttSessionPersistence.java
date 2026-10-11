package com.hmdm.launcher.mqtt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Endpoint-isolated protocol files, with one-time import from the legacy Paho directory. */
public final class MqttSessionPersistence {
    private MqttSessionPersistence() {}

    public static File directory(File privateRoot, File legacyRoot, String legacyEndpoint,
                                 String endpoint, String clientId) throws IOException {
        File target = new File(privateRoot, digest(endpoint + "\n" + clientId));
        if (target.isDirectory()) return target;
        Files.createDirectories(privateRoot.toPath());
        File source = legacyRoot == null ? null : new File(legacyRoot, safe(clientId) + "-" + safe(legacyEndpoint));
        if (source == null || !source.isDirectory()) {
            Files.createDirectories(target.toPath());
            return target;
        }
        Path staging = Files.createTempDirectory(privateRoot.toPath(), "import-");
        try {
            Path session = Files.createDirectory(staging.resolve(safe(clientId) + "-" + safe(endpoint)));
            File[] files = source.listFiles();
            if (files == null) throw new IOException("Cannot read legacy MQTT protocol state");
            for (File file : files) {
                // Lock files belong to the terminated legacy process; copy only protocol/backup files.
                if (file.isFile() && (file.getName().endsWith(".msg") || file.getName().endsWith(".bup")))
                    Files.copy(file.toPath(), session.resolve(file.getName()));
            }
            Files.move(staging, target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(staging)) {
                File[] sessions = staging.toFile().listFiles();
                if (sessions != null) for (File session : sessions) {
                    File[] leftovers = session.listFiles();
                    if (leftovers != null) for (File file : leftovers) Files.deleteIfExists(file.toPath());
                    Files.deleteIfExists(session.toPath());
                }
                Files.deleteIfExists(staging);
            }
        }
        return target;
    }

    private static String safe(String value) {
        StringBuilder key = new StringBuilder();
        for (char c : value.toCharArray()) if (Character.isJavaIdentifierPart(c) || c == '-') key.append(c);
        return key.toString();
    }
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
