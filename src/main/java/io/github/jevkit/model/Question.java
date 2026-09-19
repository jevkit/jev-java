package io.github.jevkit.model;

/**
 * A typed question asked about a request's state. The type parameter is the answer the question produces, which is what
 * lets a response return a {@link ChoiceAnswer} for a {@link ChoiceQuestion} without a cast.
 *
 * <p>Adding a type to {@code permits} is a breaking change for callers that switch over it exhaustively.
 *
 * @param <A> the answer type this question produces
 */
public sealed interface Question<A extends Answer> permits ChoiceQuestion, ScoreQuestion, NoulQuestion {

    /**
     * What the model should decide: a {@code String}, or structured content (an unmodifiable {@code Map} or
     * {@code List}) when the question has several parts.
     *
     * @return the instructions, never {@code null}
     */
    Object instructions();
}
