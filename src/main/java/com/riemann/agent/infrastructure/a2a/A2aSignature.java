package com.riemann.agent.infrastructure.a2a;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 request signing shared by {@link A2aClient} (outbound) and {@code
 * A2aAuthenticationFilter} (inbound).
 *
 * <p>The signed payload is {@code method \n path \n timestamp \n agentId \n sha256(body)}.
 * Including the body and the agent id means a captured request cannot be replayed with a different
 * prompt or a different claimed identity, even inside the timestamp window.
 */
public final class A2aSignature {

    public static final String AGENT_ID_HEADER = "X-Agent-Id";
    public static final String TIMESTAMP_HEADER = "X-Agent-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Agent-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private A2aSignature() {}

    /** Canonical string that both peers sign. */
    public static String canonical(
            String method, String path, String timestamp, String agentId, byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        return method + "\n" + path + "\n" + timestamp + "\n" + agentId + "\n" + sha256Hex(payload);
    }

    /** Lowercase hex HMAC-SHA256 of {@link #canonical}. */
    public static String sign(
            String secret,
            String method,
            String path,
            String timestamp,
            String agentId,
            byte[] body) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("A2A shared secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest =
                    mac.doFinal(
                            canonical(method, path, timestamp, agentId, body)
                                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute A2A signature", e);
        }
    }

    /** Constant-time comparison; returns false for any malformed input. */
    public static boolean verify(
            String secret,
            String method,
            String path,
            String timestamp,
            String agentId,
            byte[] body,
            String providedSignature) {
        if (secret == null
                || secret.isEmpty()
                || providedSignature == null
                || providedSignature.isBlank()) {
            return false;
        }
        String expected = sign(secret, method, path, timestamp, agentId, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature
                        .trim()
                        .toLowerCase(java.util.Locale.ROOT)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
