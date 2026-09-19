package io.github.jevkit.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionTest {

    @Test
    void choiceKeepsOptionOrderAndAllowsUndescribedOptions() {
        ChoiceQuestion question = ChoiceQuestion.builder("Which team?")
                .option("technical", "Errors, crashes")
                .option("billing", "Invoices, charges")
                .option("other")
                .build();

        assertEquals(List.of("technical", "billing", "other"), List.copyOf(question.options().keySet()));
        assertEquals("Errors, crashes", question.options().get("technical"));
        assertNull(question.options().get("other"));
        assertTrue(question.options().containsKey("other"));
    }

    @Test
    void choiceNeedsTwoDistinctNamedOptions() {
        assertThrows(IllegalArgumentException.class, () -> ChoiceQuestion.of("Which?", "only"));
        assertThrows(IllegalArgumentException.class, () -> ChoiceQuestion.builder("Which?").option("a").option("a"));
        assertThrows(IllegalArgumentException.class, () -> ChoiceQuestion.builder("Which?").option(" "));
        assertThrows(NullPointerException.class, () -> ChoiceQuestion.of(null, "a", "b"));
    }

    @Test
    void choiceCanBeBuiltFromAMapThroughItsConstructor() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("yes", null);
        options.put("no", Map.of("what", "Anything else"));

        ChoiceQuestion question = new ChoiceQuestion("Is it spam?", options);
        options.put("maybe", null);

        assertEquals(List.of("yes", "no"), List.copyOf(question.options().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> question.options().put("maybe", null));
    }

    @Test
    void scoreKeepsLevelOrderAndNeedsTwoNonNullLevels() {
        ScoreQuestion question = ScoreQuestion.of("How upset?", "Calm", "Annoyed", "Angry");

        assertEquals(List.of("Calm", "Annoyed", "Angry"), question.levels());
        assertThrows(IllegalArgumentException.class, () -> ScoreQuestion.of("How?", "Only one"));

        List<Object> withNull = new ArrayList<>();
        withNull.add("Low");
        withNull.add(null);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ScoreQuestion("How?", withNull));
        assertEquals("levels[1] must not be null", exception.getMessage());
    }

    @Test
    void scoreBuilderAddsLevelsInOrder() {
        ScoreQuestion question = ScoreQuestion.builder("How severe?")
                .level("Cosmetic")
                .level(Map.of("what", "Broken feature", "examples", List.of("Checkout fails")))
                .build();

        assertEquals("Cosmetic", question.levels().get(0));
        assertEquals(Map.of("what", "Broken feature", "examples", List.of("Checkout fails")), question.levels().get(1));
    }

    @Test
    void noulCriteriaAreOptional() {
        NoulQuestion plain = new NoulQuestion("Is this about a charge?");
        NoulQuestion described = new NoulQuestion("Is it urgent?", "Mentions a deadline or an outage", "Can wait for a normal reply");

        assertNull(plain.whenTrue());
        assertNull(plain.whenFalse());
        assertEquals("Mentions a deadline or an outage", described.whenTrue());
        assertEquals("Can wait for a normal reply", described.whenFalse());
    }

    @Test
    void structuredInstructionsAreCopied() {
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("question", "Is `email` phishing?");

        NoulQuestion question = new NoulQuestion(instructions);
        instructions.put("question", "changed");

        assertEquals(Map.of("question", "Is `email` phishing?"), question.instructions());
    }
}
