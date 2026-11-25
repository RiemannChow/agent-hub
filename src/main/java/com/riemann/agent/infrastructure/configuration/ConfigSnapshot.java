package com.riemann.agent.infrastructure.configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Immutable configuration snapshot that a run freezes at start time. */
public record ConfigSnapshot(
        String version, String checksum, Instant effectiveAt, Map<String, String> values) {

    public static ConfigSnapshot of(String version, Map<String, String> values) {
        Map<String, String> snapshot = new TreeMap<>(values == null ? Map.of() : values);
        return new ConfigSnapshot(version, checksum(snapshot), Instant.now(), Map.copyOf(snapshot));
    }

    public String value(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public int intValue(String key, int defaultValue) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public boolean boolValue(String key, boolean defaultValue) {
        String raw = values.get(key);
        return raw == null || raw.isBlank() ? defaultValue : Boolean.parseBoolean(raw);
    }

    private static String checksum(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        values.forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(builder.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
