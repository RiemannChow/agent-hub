package com.riemann.agent.infrastructure.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class A2aEndpointGuardTest {

    private final A2aEndpointGuard guard = new A2aEndpointGuard(List.of());

    @Test
    void acceptsAValidHttpsEndpoint() {
        assertThat(guard.validate("https://agent.example.com/a2a").getHost())
                .isEqualTo("agent.example.com");
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> guard.validate("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    @Test
    void rejectsCredentialsInUrl() {
        assertThatThrownBy(() -> guard.validate("http://user:secret@agent.example.com/a2a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void rejectsLinkLocalMetadataAndUnspecifiedAddresses() {
        for (String host : List.of("169.254.169.254", "0.0.0.0", "224.0.0.1")) {
            assertThatThrownBy(() -> guard.validate("http://" + host + ":8080/a2a"))
                    .as(host)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blocked");
        }
    }

    @Test
    void loopbackIsAllowedForTheBundledSingleHostDemo() {
        assertThat(guard.validate("http://localhost:8080/a2a").getHost()).isEqualTo("localhost");
        assertThat(guard.validate("http://127.0.0.1:8080/a2a").getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsBlankEndpoint() {
        assertThatThrownBy(() -> guard.validate("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowlistIsEnforcedWhenConfigured() {
        A2aEndpointGuard strict = new A2aEndpointGuard(List.of("peer.internal"));
        assertThat(strict.validate("http://peer.internal:8080/a2a").getHost())
                .isEqualTo("peer.internal");
        assertThatThrownBy(() -> strict.validate("http://evil.example.com/a2a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void privatePeersAreStillReachableWithoutAllowlist() {
        // Nacos-discovered peers commonly live on 10/8; only link-local/loopback are blocked.
        assertThat(guard.validate("http://10.0.0.5:8080/a2a").getHost()).isEqualTo("10.0.0.5");
    }
}
