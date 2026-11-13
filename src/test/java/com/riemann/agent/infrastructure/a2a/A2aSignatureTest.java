package com.riemann.agent.infrastructure.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Regression tests for the HMAC request signing used by both A2A peers (issue #25). */
class A2aSignatureTest {

    private static final String SECRET = "unit-test-secret";
    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"method\":\"message/send\"}";

    private static byte[] body(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(String timestamp, String agentId, byte[] payload) {
        return A2aSignature.sign(SECRET, "POST", "/a2a", timestamp, agentId, payload);
    }

    @Test
    void signatureIsDeterministicAndVerifies() {
        String sig = sign("1700000000", "agent-a", body(BODY));
        assertThat(sig).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(
                        A2aSignature.verify(
                                SECRET, "POST", "/a2a", "1700000000", "agent-a", body(BODY), sig))
                .isTrue();
    }

    @Test
    void tamperingWithBodyAgentOrTimestampInvalidatesSignature() {
        String sig = sign("1700000000", "agent-a", body(BODY));
        assertThat(
                        A2aSignature.verify(
                                SECRET,
                                "POST",
                                "/a2a",
                                "1700000000",
                                "agent-a",
                                body(BODY + " "),
                                sig))
                .isFalse();
        assertThat(
                        A2aSignature.verify(
                                SECRET, "POST", "/a2a", "1700000000", "agent-b", body(BODY), sig))
                .isFalse();
        assertThat(
                        A2aSignature.verify(
                                SECRET, "POST", "/a2a", "1700000001", "agent-a", body(BODY), sig))
                .isFalse();
    }

    @Test
    void tamperingWithMethodOrPathInvalidatesSignature() {
        String sig = sign("1700000000", "agent-a", body(BODY));
        assertThat(
                        A2aSignature.verify(
                                SECRET, "GET", "/a2a", "1700000000", "agent-a", body(BODY), sig))
                .isFalse();
        assertThat(
                        A2aSignature.verify(
                                SECRET, "POST", "/a2a/", "1700000000", "agent-a", body(BODY), sig))
                .isFalse();
    }

    @Test
    void differentSecretProducesDifferentSignature() {
        String sig = sign("1700000000", "agent-a", body(BODY));
        assertThat(
                        A2aSignature.verify(
                                "another-secret",
                                "POST",
                                "/a2a",
                                "1700000000",
                                "agent-a",
                                body(BODY),
                                sig))
                .isFalse();
    }

    @Test
    void uppercaseSignatureIsAccepted() {
        String sig = sign("1700000000", "agent-a", body(BODY)).toUpperCase();
        assertThat(
                        A2aSignature.verify(
                                SECRET, "POST", "/a2a", "1700000000", "agent-a", body(BODY), sig))
                .isTrue();
    }

    @Test
    void emptySecretCannotSignOrVerify() {
        assertThatThrownBy(() -> A2aSignature.sign("", "POST", "/a2a", "1", "a", body(BODY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(A2aSignature.verify("", "POST", "/a2a", "1", "a", body(BODY), "deadbeef"))
                .isFalse();
        assertThat(A2aSignature.verify(SECRET, "POST", "/a2a", "1", "a", body(BODY), null))
                .isFalse();
    }

    @Test
    void canonicalStringBindsTheBody() {
        String one = A2aSignature.canonical("POST", "/a2a", "1", "a", body("one"));
        String two = A2aSignature.canonical("POST", "/a2a", "1", "a", body("two"));
        assertThat(one).isNotEqualTo(two).contains("a2a");
    }
}
