package com.riemann.agent.infrastructure.configuration;

/** Supplies the current immutable configuration snapshot. */
public interface ConfigSnapshotProvider {

    ConfigSnapshot current();
}
