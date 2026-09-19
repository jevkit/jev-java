package io.github.jevkit;

import io.github.jevkit.model.Answer;
import io.github.jevkit.model.ChoiceQuestion;
import io.github.jevkit.model.NoulQuestion;
import io.github.jevkit.model.Question;
import io.github.jevkit.model.ScoreQuestion;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the {@code instanceof} chain in {@link AnswerTypes}: it fails when a question type is added to
 * {@link Question}'s {@code permits} without being mapped to its answer type.
 */
class AnswerTypesTest {

    @Test
    void everyQuestionTypeMapsToADistinctAnswerType() {
        List<Question<?>> oneOfEach = List.of(
                ChoiceQuestion.of("Which?", "a", "b"),
                ScoreQuestion.of("How much?", "low", "high"),
                new NoulQuestion("Yes?"));

        Set<Class<?>> covered = oneOfEach.stream().map(Object::getClass).collect(Collectors.toSet());
        assertEquals(Set.of(Question.class.getPermittedSubclasses()), covered,
                "Add the new question type to oneOfEach and to AnswerTypes.expectedFor");

        Set<Class<? extends Answer>> answerTypes = oneOfEach.stream().map(AnswerTypes::expectedFor).collect(Collectors.toSet());
        assertEquals(Set.copyOf(Arrays.asList(Answer.class.getPermittedSubclasses())), answerTypes);
    }
}
