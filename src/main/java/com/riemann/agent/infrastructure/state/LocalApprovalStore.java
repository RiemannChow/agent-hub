package com.riemann.agent.infrastructure.state;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Original in-process approval metadata store. */
@Component
@ConditionalOnProperty(name = "agent.state.backend", havingValue = "local", matchIfMissing = true)
public class LocalApprovalStore implements ApprovalStore {

    private final Map<String, ApprovalMetadata> pending = new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalMetadata metadata) {
        pending.put(metadata.id(), metadata);
    }

    @Override
    public Optional<ApprovalMetadata> view(String id) {
        if (id == null) {
            return Optional.empty();
        }
        ApprovalMetadata metadata = pending.get(id);
        if (metadata == null) {
            return Optional.empty();
        }
        if (!Instant.now().isBefore(metadata.expiresAt())) {
            pending.remove(id, metadata);
            return Optional.empty();
        }
        return Optional.of(metadata);
    }

    @Override
    public Claim claim(String id, String userId) {
        ApprovalMetadata metadata = view(id).orElse(null);
        if (metadata == null) {
            return Claim.notFound();
        }
        if (!metadata.userId().equals(userId)) {
            return Claim.forbidden();
        }
        pending.remove(id, metadata);
        return new Claim(ClaimStatus.CLAIMED, metadata);
    }

    @Override
    public void remove(String id) {
        pending.remove(id);
    }
}
