package com.riemann.agent.infrastructure.toolkit.registry;

import java.util.List;

/** Seeds the registry with descriptors for the tools currently shipped by agent-hub. */
public final class BuiltInToolDescriptors {

    public static final String VERSION = "1.0.0";

    private BuiltInToolDescriptors() {}

    public static List<ToolDescriptor> all() {
        return List.of(
                read(
                        "seek_sql_query",
                        ToolDescriptor.Risk.MEDIUM,
                        "Read-only SQL over the SeekDB whitelist"),
                read("seek_hybrid_search", "Hybrid vector + full-text search"),
                read("seek_book_search", "Book lookup by keyword"),
                read("seek_book_count_by_year", "Count books by publication year"),
                write(
                        "seek_fork_sandbox",
                        ToolDescriptor.Risk.MEDIUM,
                        "Fork a writable SQL sandbox"),
                write(
                        "seek_write_sandbox",
                        ToolDescriptor.Risk.HIGH,
                        "Write inside the SQL sandbox"),
                write("seek_commit_sandbox", ToolDescriptor.Risk.HIGH, "Commit sandbox changes"),
                write(
                        "seek_discard_sandbox",
                        ToolDescriptor.Risk.MEDIUM,
                        "Discard sandbox changes"),
                write("save_memory", ToolDescriptor.Risk.HIGH, "Persist a long-term memory fact"),
                read("ob_doc_search", "OceanBase documentation search"),
                read("ob_topology", "OceanBase topology lookup"),
                read("ob_code_search", "OceanBase code symbol search"),
                read("ob_concept", "OceanBase concept lookup"),
                read("ob_cypher", ToolDescriptor.Risk.MEDIUM, "Read-only Cypher query"));
    }

    public static void registerAll(ToolRegistry registry) {
        all().forEach(registry::register);
    }

    private static ToolDescriptor read(String name, String description) {
        return read(name, ToolDescriptor.Risk.LOW, description);
    }

    private static ToolDescriptor read(String name, ToolDescriptor.Risk risk, String description) {
        return descriptor(name, risk, ToolDescriptor.SideEffect.READ, description);
    }

    private static ToolDescriptor write(String name, ToolDescriptor.Risk risk, String description) {
        return descriptor(name, risk, ToolDescriptor.SideEffect.WRITE, description);
    }

    private static ToolDescriptor descriptor(
            String name,
            ToolDescriptor.Risk risk,
            ToolDescriptor.SideEffect sideEffect,
            String description) {
        return new ToolDescriptor(
                name,
                VERSION,
                "1.0.0",
                risk,
                sideEffect,
                ToolDescriptor.Status.ACTIVE,
                "agent-hub",
                description);
    }
}
