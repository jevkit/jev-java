package io.github.jevkit.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentTest {

    private record Ticket(String subject, int priority, List<String> tags) {
    }

    private enum Channel { EMAIL, CHAT }

    @Test
    void scalarsPassThrough() {
        assertEquals("text", Content.copyOf("text", "x"));
        assertEquals(true, Content.copyOf(true, "x"));
        assertEquals(42, Content.copyOf(42, "x"));
        assertEquals(1.5, Content.copyOf(1.5, "x"));
        assertEquals(new BigDecimal("10.25"), Content.copyOf(new BigDecimal("10.25"), "x"));
        assertNull(Content.copyOf(null, "x"));
    }

    @Test
    void recordsBecomeMapsInComponentOrderEvenWhenPrivate() {
        Object copy = Content.copyOf(new Ticket("Refund", 2, List.of("billing")), "state");

        Map<?, ?> map = (Map<?, ?>) copy;
        assertEquals(List.of("subject", "priority", "tags"), List.copyOf(map.keySet()));
        assertEquals("Refund", map.get("subject"));
        assertEquals(2, map.get("priority"));
        assertEquals(List.of("billing"), map.get("tags"));
    }

    @Test
    void enumsBecomeTheirNameAndArraysBecomeLists() {
        assertEquals("CHAT", Content.copyOf(Channel.CHAT, "x"));
        assertEquals(List.of("a", "b"), Content.copyOf(new String[]{"a", "b"}, "x"));
    }

    @Test
    void mapsKeepInsertionOrderAndAllowNullValues() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("zeta", 1);
        original.put("alpha", null);
        original.put("mid", Map.of("inner", true));

        Map<?, ?> copy = (Map<?, ?>) Content.copyOf(original, "state");

        assertEquals(List.of("zeta", "alpha", "mid"), List.copyOf(copy.keySet()));
        assertNull(copy.get("alpha"));
        assertEquals(Map.of("inner", true), copy.get("mid"));
    }

    @Test
    void copiesAreDeepAndImmutable() {
        List<Object> tags = new ArrayList<>(List.of("a"));
        Map<String, Object> original = new HashMap<>(Map.of("tags", tags));

        Map<?, ?> copy = (Map<?, ?>) Content.copyOf(original, "state");
        tags.add("added later");
        original.put("other", 1);

        assertEquals(List.of("a"), copy.get("tags"));
        assertEquals(1, copy.size());
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) copy.get("tags")).clear());
    }

    @Test
    void rejectsWhatJsonCannotRepresentAndNamesThePath() {
        IllegalArgumentException nan = assertThrows(IllegalArgumentException.class,
                () -> Content.copyOf(Map.of("score", Double.NaN), "state"));
        assertTrue(nan.getMessage().startsWith("state.score:"), nan.getMessage());

        IllegalArgumentException key = assertThrows(IllegalArgumentException.class,
                () -> Content.copyOf(Map.of(1, "one"), "state"));
        assertTrue(key.getMessage().contains("map keys must be strings"), key.getMessage());

        IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                () -> Content.copyOf(List.of("ok", new Object()), "instructions"));
        assertTrue(type.getMessage().startsWith("instructions[1]: unsupported type java.lang.Object"), type.getMessage());
    }

    @Test
    void rejectsCycles() {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> Content.copyOf(cycle, "state"));
        assertTrue(exception.getMessage().contains("cycle"), exception.getMessage());
    }
}
