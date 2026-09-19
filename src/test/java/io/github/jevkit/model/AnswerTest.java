package io.github.jevkit.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerTest {

    private static final List<String> SEVERITY = List.of("Nada grave", "Leve", "Moderado", "Grave", "Catastrófico");

    @Test
    void noulThresholdIsInclusive() {
        NoulAnswer answer = new NoulAnswer(0.98);

        assertTrue(answer.isTrue(0.98));
        assertFalse(answer.isTrue(0.99));
        assertThrows(IllegalArgumentException.class, () -> answer.isTrue(1.5));
    }

    @Test
    void noulValueMustBeAProbability() {
        assertThrows(IllegalArgumentException.class, () -> new NoulAnswer(-0.01));
        assertThrows(IllegalArgumentException.class, () -> new NoulAnswer(1.01));
        assertThrows(IllegalArgumentException.class, () -> new NoulAnswer(Double.NaN));
    }

    @Test
    void choiceMustBeOneOfTheOptions() {
        ChoiceAnswer answer = new ChoiceAnswer("critica", 1.0, Map.of("critica", 1.0, "media", 0.0, "baja", 0.0));

        assertEquals(1.0, answer.probability("critica"));
        assertEquals(0.0, answer.probability("baja"));
        assertThrows(IllegalArgumentException.class, () -> answer.probability("unknown"));
        assertThrows(IllegalArgumentException.class, () -> new ChoiceAnswer("missing", 1.0, Map.of("a", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new ChoiceAnswer("a", 1.2, Map.of("a", 1.0)));
    }

    @Test
    void scoreHelpersMatchARealApiResponse() {
        // Values returned by jev-1.13.0 on 2026-09-18 for "El servidor se cayó y nadie puede entrar"
        ScoreAnswer answer = new ScoreAnswer(2.96, 0.87, SEVERITY, List.of(0.0, 0.0, 0.09, 0.85, 0.06));

        assertEquals(3, answer.mostLikelyLevel());
        assertEquals("Grave", answer.legend().get(answer.mostLikelyLevel()));
        assertEquals(0.74, answer.normalizedScore(), 1e-9);
    }

    @Test
    void mostLikelyLevelPrefersTheLowestLevelOnATie() {
        ScoreAnswer answer = new ScoreAnswer(1.0, 0.0, List.of("low", "mid", "high"), List.of(0.5, 0.0, 0.5));

        assertEquals(0, answer.mostLikelyLevel());
    }

    @Test
    void scoreRejectsInconsistentShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScoreAnswer(1.0, 0.5, List.of("a", "b", "c"), List.of(0.5, 0.5)));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoreAnswer(2.5, 0.5, List.of("a", "b", "c"), List.of(0.0, 0.5, 0.5)));
        assertThrows(IllegalArgumentException.class,
                () -> new ScoreAnswer(0.0, 0.5, List.of("only"), List.of(1.0)));
    }

    @Test
    void usageCountsCannotBeNegative() {
        assertEquals(342, new Usage(342, 42).inputTokens());
        assertThrows(IllegalArgumentException.class, () -> new Usage(-1, 0));
    }
}
