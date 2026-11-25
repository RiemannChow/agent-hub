package com.riemann.agent.infrastructure.toolkit.registry;

/** Versioned metadata for one tool. */
public record ToolDescriptor(
        String name,
        String version,
        String schemaVersion,
        Risk risk,
        SideEffect sideEffect,
        Status status,
        String owner,
        String description) {

    public enum Risk {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum SideEffect {
        NONE,
        READ,
        WRITE,
        EXTERNAL
    }

    public enum Status {
        ACTIVE,
        DEPRECATED,
        BLOCKED
    }

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("tool version must not be blank");
        }
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? "1.0.0" : schemaVersion;
        risk = risk == null ? Risk.MEDIUM : risk;
        sideEffect = sideEffect == null ? SideEffect.READ : sideEffect;
        status = status == null ? Status.ACTIVE : status;
        owner = owner == null || owner.isBlank() ? "unassigned" : owner;
        description = description == null ? "" : description;
    }
}
