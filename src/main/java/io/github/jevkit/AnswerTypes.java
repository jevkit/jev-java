package io.github.jevkit;

import io.github.jevkit.model.Answer;
import io.github.jevkit.model.ChoiceAnswer;
import io.github.jevkit.model.ChoiceQuestion;
import io.github.jevkit.model.NoulAnswer;
import io.github.jevkit.model.NoulQuestion;
import io.github.jevkit.model.Question;
import io.github.jevkit.model.ScoreAnswer;
import io.github.jevkit.model.ScoreQuestion;

import java.util.Set;

/**
 * Which answer each question type produces, and whether an answer fits the question it answers.
 *
 * <p>Uses {@code instanceof} chains because the library compiles for Java 17. {@code AnswerTypesTest} fails when a
 * question type is added to {@link Question}'s {@code permits} without being handled here.
 */
final class AnswerTypes {

    private AnswerTypes() {
    }

    static Class<? extends Answer> expectedFor(Question<?> question) {
        if (question instanceof ChoiceQuestion) {
            return ChoiceAnswer.class;
        }

        if (question instanceof ScoreQuestion) {
            return ScoreAnswer.class;
        }

        if (question instanceof NoulQuestion) {
            return NoulAnswer.class;
        }

        throw new AssertionError("Unhandled question type " + question.getClass().getName());
    }

    /**
     * @param name     the question name, for the message
     * @param question the question that was asked
     * @param answer   the answer received for it
     * @throws IllegalArgumentException if the answer is of another type, or does not match the question's options or
     *                                  levels
     */
    static void requireFits(String name, Question<?> question, Answer answer) {
        Class<? extends Answer> expected = expectedFor(question);

        if (!expected.isInstance(answer)) {
            throw new IllegalArgumentException("Question '" + name + "' expects a " + expected.getSimpleName()
                    + ", got a " + answer.getClass().getSimpleName());
        }

        if (question instanceof ChoiceQuestion choice) {
            Set<String> asked = choice.options().keySet();
            Set<String> answered = ((ChoiceAnswer) answer).probabilities().keySet();

            if (!asked.equals(answered)) {
                throw new IllegalArgumentException("Question '" + name + "' has options " + asked
                        + " but the answer has probabilities for " + answered);
            }
        }

        if (question instanceof ScoreQuestion score) {
            int asked = score.levels().size();
            int answered = ((ScoreAnswer) answer).probabilities().size();

            if (asked != answered) {
                throw new IllegalArgumentException("Question '" + name + "' has " + asked
                        + " levels but the answer has " + answered);
            }
        }
    }
}
