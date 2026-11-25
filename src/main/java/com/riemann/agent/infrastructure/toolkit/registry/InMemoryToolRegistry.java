package com.riemann.agent.infrastructure.toolkit.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory {@link ToolRegistry}. */
public class InMemoryToolRegistry implements ToolRegistry {

    private static final Comparator<String> VERSION =
            Comparator.comparing(
                    InMemoryToolRegistry::versionParts,
                    (a, b) -> {
                        int length = Math.max(a.length, b.length);
                        for (int i = 0; i < length; i++) {
                            int left = i < a.length ? a[i] : 0;
                            int right = i < b.length ? b[i] : 0;
                            if (left != right) {
                                return Integer.compare(left, right);
                            }
                        }
                        return 0;
                    });

    private final Map<String, Map<String, ToolDescriptor>> byName = new ConcurrentHashMap<>();

    @Override
    public void register(ToolDescriptor descriptor) {
        Map<String, ToolDescriptor> versions =
                byName.computeIfAbsent(descriptor.name(), key -> new LinkedHashMap<>());
        synchronized (versions) {
            if (versions.containsKey(descriptor.version())) {
                throw new IllegalArgumentException(
                        "Tool version already registered: "
                                + descriptor.name()
                                + "@"
                                + descriptor.version());
            }
            versions.put(descriptor.version(), descriptor);
        }
    }

    @Override
    public Optional<ToolDescriptor> resolve(String name) {
        Map<String, ToolDescriptor> versions = byName.get(name);
        if (versions == null) {
            return Optional.empty();
        }
        synchronized (versions) {
            return versions.values().stream()
                    .filter(d -> d.status() == ToolDescriptor.Status.ACTIVE)
                    .max(Comparator.comparing(ToolDescriptor::version, VERSION));
        }
    }

    @Override
    public Optional<ToolDescriptor> resolve(String name, String version) {
        Map<String, ToolDescriptor> versions = byName.get(name);
        if (versions == null) {
            return Optional.empty();
        }
        synchronized (versions) {
            return Optional.ofNullable(versions.get(version));
        }
    }

    @Override
    public List<ToolDescriptor> list() {
        List<ToolDescriptor> all = new ArrayList<>();
        byName.values()
                .forEach(
                        versions -> {
                            synchronized (versions) {
                                all.addAll(versions.values());
                            }
                        });
        return List.copyOf(all);
    }

    private static int[] versionParts(String version) {
        String[] parts = version.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                result[i] = 0;
            }
        }
        return result;
    }
}
