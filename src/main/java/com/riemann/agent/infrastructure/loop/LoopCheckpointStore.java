package com.riemann.agent.infrastructure.loop;

import java.util.Optional;

/** Persistence abstraction for loop checkpoints. */
public interface LoopCheckpointStore {

    void save(LoopCheckpoint checkpoint);

    Optional<LoopCheckpoint> latest(String runId);

    void delete(String runId);
}
