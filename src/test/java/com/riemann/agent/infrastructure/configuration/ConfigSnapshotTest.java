package com.riemann.agent.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigSnapshotTest {

    @Test
    void checksumIsStableAcrossMapOrder() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("a", "1");
        first.put("b", "2");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("b", "2");
        second.put("a", "1");

        ConfigSnapshot snapshotA = ConfigSnapshot.of("v1", first);
        ConfigSnapshot snapshotB = ConfigSnapshot.of("v1", second);

        assertEquals(snapshotA.checksum(), snapshotB.checksum());
        assertEquals("v1", snapshotA.version());
    }

    @Test
    void checksumChangesWithValues() {
        ConfigSnapshot a = ConfigSnapshot.of("v1", Map.of("a", "1"));
        ConfigSnapshot b = ConfigSnapshot.of("v1", Map.of("a", "2"));

        assertNotEquals(a.checksum(), b.checksum());
    }

    @Test
    void readsTypedValuesWithDefaults() {
        ConfigSnapshot snapshot =
                ConfigSnapshot.of("v1", Map.of("agent.loop.max-iters", "50", "flag", "true"));

        assertEquals(50, snapshot.intValue("agent.loop.max-iters", 30));
        assertEquals(30, snapshot.intValue("missing", 30));
        assertEquals(true, snapshot.boolValue("flag", false));
        assertEquals("fallback", snapshot.value("missing", "fallback"));
    }
}
