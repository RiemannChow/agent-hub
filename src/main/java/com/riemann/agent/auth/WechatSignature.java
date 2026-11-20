package com.riemann.agent.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 微信回调签名：HMAC-SHA256(ticket|openId|timestamp)，常量时间比较。 */
public final class WechatSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private WechatSignature() {}

    public static String sign(String secret, String ticket, String openId, String timestamp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw =
                    mac.doFinal(
                            (ticket + "|" + openId + "|" + timestamp)
                                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    public static boolean verify(
            String secret, String ticket, String openId, String timestamp, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        String expected = sign(secret, ticket, openId, timestamp);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
