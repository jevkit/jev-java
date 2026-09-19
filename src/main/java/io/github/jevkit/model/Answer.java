package io.github.jevkit.model;

/**
 * The answer to one {@link Question}. Every answer the SDK returns was validated when the response was parsed, so its
 * values are always present and within range.
 *
 * <p>Adding a type to {@code permits} is a breaking change for callers that switch over it exhaustively.
 */
public sealed interface Answer permits ChoiceAnswer, ScoreAnswer, NoulAnswer {
}
