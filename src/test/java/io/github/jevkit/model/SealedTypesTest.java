package io.github.jevkit.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The library compiles for Java 17, where {@code switch} cannot match on types, so code that handles every question or
 * answer type uses {@code instanceof} chains. This test fails when a type is added to {@code permits}, as a reminder to
 * update those chains (request building and response parsing) and the CHANGELOG, since it is a breaking change.
 */
class SealedTypesTest {

    @Test
    void questionTypesAreKnown() {
        assertEquals(Set.of(ChoiceQuestion.class, ScoreQuestion.class, NoulQuestion.class), permitted(Question.class));
    }

    @Test
    void answerTypesAreKnown() {
        assertEquals(Set.of(ChoiceAnswer.class, ScoreAnswer.class, NoulAnswer.class), permitted(Answer.class));
    }

    private static Set<Class<?>> permitted(Class<?> sealedType) {
        return Arrays.stream(sealedType.getPermittedSubclasses()).collect(Collectors.toSet());
    }
}
