package com.riemann.agent.presentation.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.riemann.agent.presentation.a2a.protocol.Message;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A2A identities must never collide with (or impersonate) a real application user. */
class A2aIdentityTest {

    private static Message message(String userId) {
        return new Message("user", List.of(), userId == null ? Map.of() : Map.of("userId", userId));
    }

    @Test
    void headerIsPlacedInTheA2aNamespace() {
        assertThat(A2aRpcController.resolveUserId("peer-1", message(null))).isEqualTo("a2a:peer-1");
    }

    @Test
    void metadataCannotImpersonateARealUser() {
        String id = A2aRpcController.resolveUserId(null, message("alice"));
        assertThat(id).startsWith("a2a:");
        assertThat(id).isEqualTo("a2a:alice");
        assertThat(id).isNotEqualTo("alice");
    }

    @Test
    void anonymousFallbackIsAlsoNamespaced() {
        assertThat(A2aRpcController.resolveUserId(null, message(null))).isEqualTo("a2a:anonymous");
    }

    @Test
    void existingNamespacePrefixIsNotDuplicated() {
        assertThat(A2aRpcController.resolveUserId(null, message("a2a:peer-2")))
                .isEqualTo("a2a:peer-2");
    }

    @Test
    void malformedAgentIdFallsBackToAnonymous() {
        assertThat(A2aRpcController.resolveUserId("peer\nX-Injected: 1", message(null)))
                .isEqualTo("a2a:anonymous");
    }
}
