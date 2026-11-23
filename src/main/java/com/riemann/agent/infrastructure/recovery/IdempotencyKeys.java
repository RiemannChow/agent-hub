package com.riemann.agent.infrastructure.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 幂等键：conversation + turn + tool + 规范化参数 的确定性哈希。
 *
 * <p>重试同一个工具调用会得到同样的键，因此写工具可以用它做去重；读工具用它做缓存键也安全。 参数先按 key 排序再序列化，避免 {"a":1,"b":2} 与 {"b":2,"a":1}
 * 产生不同键。
 */
@Component
public class IdempotencyKeys {

    /** 生成 16 字符的十六进制幂等键（SHA-256 前 8 字节）。 */
    public String of(String conversationId, String turnId, String tool, Map<String, Object> args) {
        StringBuilder raw = new StringBuilder();
        raw.append(safe(conversationId))
                .append('|')
                .append(safe(turnId))
                .append('|')
                .append(safe(tool))
                .append('|');
        if (args != null) {
            new TreeMap<>(args)
                    .forEach(
                            (k, v) ->
                                    raw.append(k)
                                            .append('=')
                                            .append(String.valueOf(v))
                                            .append(';'));
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(java.util.Arrays.copyOf(digest, 8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
