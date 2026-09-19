package io.github.jevkit.model;

/**
 * The answer to a {@link NoulQuestion}.
 *
 * <p>Noul answers carry no confidence. A value near 0.5 is the model saying it cannot tell, so treat the band around
 * it as "unsure" rather than splitting it at exactly 0.5 when the decision matters.
 *
 * @param value probability that the answer is yes, from 0 to 1
 */
public record NoulAnswer(double value) implements Answer {

    /**
     * Validates that the value is a probability.
     *
     * @throws IllegalArgumentException if {@code value} is not between 0 and 1
     */
    public NoulAnswer {
        Probabilities.requireProbability(value, "value");
    }

    /**
     * Turns the probability into a yes/no decision.
     *
     * @param threshold the lowest value that counts as yes, from 0 to 1; pick it for your use case and model version
     * @return whether {@link #value()} is at least {@code threshold}
     * @throws IllegalArgumentException if {@code threshold} is not between 0 and 1
     */
    public boolean isTrue(double threshold) {
        Probabilities.requireProbability(threshold, "threshold");
        return value >= threshold;
    }
}
