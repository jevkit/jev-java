package io.github.jevkit;

import io.github.jevkit.model.ChoiceAnswer;
import io.github.jevkit.model.ChoiceQuestion;
import io.github.jevkit.model.NoulAnswer;
import io.github.jevkit.model.NoulQuestion;
import io.github.jevkit.model.ScoreQuestion;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionSetTest {

    @Test
    void keepsQuestionsInTheOrderTheyWereAdded() {
        QuestionSet.Builder request = QuestionSet.builder("The export button stopped working.");
        QuestionKey<NoulAnswer> refund = request.add("refund", new NoulQuestion("Is this about a charge?"));
        QuestionKey<ChoiceAnswer> team = request.add("team", ChoiceQuestion.of("Which team?", "billing", "technical"));
        request.add("frustration", ScoreQuestion.of("How upset?", "Calm", "Angry"));

        QuestionSet questions = request.build();

        assertEquals(List.of("refund", "team", "frustration"), List.copyOf(questions.questions().keySet()));
        assertEquals("refund", refund.name());
        assertTrue(questions.contains(refund));
        assertTrue(questions.contains(team));
        assertEquals("The export button stopped working.", questions.state());
        assertEquals(Optional.empty(), questions.model());
    }

    @Test
    void rejectsBlankAndDuplicateNamesAndEmptyRequests() {
        QuestionSet.Builder request = QuestionSet.builder("state");
        request.add("urgent", new NoulQuestion("Urgent?"));

        assertThrows(IllegalArgumentException.class, () -> request.add("urgent", new NoulQuestion("Again?")));
        assertThrows(IllegalArgumentException.class, () -> request.add(" ", new NoulQuestion("Blank?")));
        assertThrows(IllegalStateException.class, () -> QuestionSet.builder("state").build());
        assertThrows(IllegalArgumentException.class, () -> QuestionSet.builder("state").model(""));
    }

    @Test
    void keysBelongToTheirOwnRequest() {
        QuestionSet.Builder first = QuestionSet.builder("a");
        QuestionKey<NoulAnswer> firstKey = first.add("urgent", new NoulQuestion("Urgent?"));
        QuestionSet.Builder second = QuestionSet.builder("b");
        second.add("urgent", new NoulQuestion("Urgent?"));

        assertFalse(second.build().contains(firstKey));
    }

    @Test
    void keysAddedAfterBuildDoNotBelongToEarlierSets() {
        QuestionSet.Builder request = QuestionSet.builder("state");
        request.add("first", new NoulQuestion("First?"));
        QuestionSet earlier = request.build();
        QuestionKey<NoulAnswer> later = request.add("second", new NoulQuestion("Second?"));

        assertFalse(earlier.contains(later));
        assertTrue(request.build().contains(later));
    }

    @Test
    void structuredStateIsCopiedAndModelCanBePinnedPerRequest() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("ticket", Map.of("body", "Export button greyed out since the update"));

        QuestionSet.Builder request = QuestionSet.builder(state).model("jev-1.13.0");
        request.add("urgent", new NoulQuestion("Is `ticket.body` urgent?"));
        QuestionSet questions = request.build();
        state.put("added", "later");

        assertEquals(Map.of("ticket", Map.of("body", "Export button greyed out since the update")), questions.state());
        assertEquals(Optional.of("jev-1.13.0"), questions.model());
    }
}
