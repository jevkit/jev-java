package io.github.jevkit.model;

import io.github.jevkit.internal.Content;

import java.util.Objects;

/**
 * A yes/no question. The answer is the probability that the answer is yes.
 *
 * <pre>{@code
 * new NoulQuestion("Is the customer asking to cancel their plan?");
 * new NoulQuestion("Does the review recommend the product?",
 *         "Says they would buy it again or tell others to",
 *         "Advises against it, or gives no recommendation either way");
 * }</pre>
 *
 * @param instructions the yes/no question, or a statement to check for truth: a {@code String} or structured content
 * @param whenTrue     optional description of what a yes means, or {@code null}
 * @param whenFalse    optional description of what a no means, or {@code null}
 */
public record NoulQuestion(Object instructions, Object whenTrue, Object whenFalse) implements Question<NoulAnswer> {

    /**
     * Validates the question and copies its content into unmodifiable structures.
     *
     * @throws IllegalArgumentException if any content cannot be represented as JSON
     */
    public NoulQuestion {
        Objects.requireNonNull(instructions, "instructions");
        instructions = Content.copyOf(instructions, "instructions");
        whenTrue = Content.copyOf(whenTrue, "whenTrue");
        whenFalse = Content.copyOf(whenFalse, "whenFalse");
    }

    /**
     * A question without descriptions of yes and no, which is enough for most questions.
     *
     * @param instructions the yes/no question: a {@code String} or structured content
     */
    public NoulQuestion(Object instructions) {
        this(instructions, null, null);
    }
}
