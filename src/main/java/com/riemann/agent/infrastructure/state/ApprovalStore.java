package com.riemann.agent.infrastructure.state;

import java.time.Instant;
import java.util.Optional;

/** Stores pending-approval metadata. The executable action stays on its originating pod. */
public interface ApprovalStore {

    void save(ApprovalMetadata metadata);

    Optional<ApprovalMetadata> view(String id);

    /** Atomically consumes an approval and checks its owner. */
    Claim claim(String id, String userId);

    void remove(String id);

    record ApprovalMetadata(
            String id,
            String userId,
            String conversationId,
            String tool,
            String description,
            Instant expiresAt) {}

    enum ClaimStatus {
        CLAIMED,
        NOT_FOUND,
        FORBIDDEN
    }

    record Claim(ClaimStatus status, ApprovalMetadata metadata) {

        public static Claim notFound() {
            return new Claim(ClaimStatus.NOT_FOUND, null);
        }

        public static Claim forbidden() {
            return new Claim(ClaimStatus.FORBIDDEN, null);
        }
    }
}
