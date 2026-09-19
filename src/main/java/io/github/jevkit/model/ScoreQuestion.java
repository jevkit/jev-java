package io.github.jevkit.model;

import io.github.jevkit.internal.Content;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Rates the state along ordered levels you describe, from the low end to the high end. Level {@code n} is the
 * {@code n}-th entry, starting at 0. The answer is a position along the levels that can fall between two of them.
 *
 * <p>Each level is judged by its own description, without its number or its neighbours, so a concrete description
 * ("checkout fails, but paying by card still works") gives the model more to go on than a relative one ("worse than
 * the previous level").
 *
 * <pre>{@code
 * ScoreQuestion.of("How reproducible is the bug report?", "No steps given", "Partial steps", "Exact steps");
 * }</pre>
 *
 * @param instructions what the model should rate: a {@code String} or structured content
 * @param levels       level descriptions from lowest to highest, at least two; a description may be structured content
 */
public record ScoreQuestion(Object instructions, List<Object> levels) implements Question<ScoreAnswer> {

    /**
     * Validates the question and copies its content into unmodifiable structures.
     *
     * @throws IllegalArgumentException if there are fewer than two levels, a level is {@code null}, or any content
     *                                  cannot be represented as JSON
     */
    public ScoreQuestion {
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(levels, "levels");
        instructions = Content.copyOf(instructions, "instructions");

        if (levels.size() < 2) {
            throw new IllegalArgumentException("A score question needs at least 2 levels, got " + levels.size());
        }

        List<Object> copy = new ArrayList<>(levels.size());

        for (int level = 0; level < levels.size(); level++) {
            Object description = levels.get(level);

            if (description == null) {
                throw new IllegalArgumentException("levels[" + level + "] must not be null");
            }

            copy.add(Content.copyOf(description, "levels[" + level + "]"));
        }

        levels = Collections.unmodifiableList(copy);
    }

    /**
     * Creates a question whose levels are plain text.
     *
     * @param instructions what the model should rate
     * @param levels       at least two level descriptions, lowest first
     * @return the question
     */
    public static ScoreQuestion of(Object instructions, String... levels) {
        return new ScoreQuestion(instructions, Arrays.asList((Object[]) levels));
    }

    /**
     * Starts a question whose levels are added one by one, lowest first.
     *
     * @param instructions what the model should rate: a {@code String} or structured content
     * @return a builder; add at least two levels, lowest first, then {@link Builder#build()}
     */
    public static Builder builder(Object instructions) {
        return new Builder(instructions);
    }

    /** Collects levels from lowest to highest. */
    public static final class Builder {

        private final Object instructions;
        private final List<Object> levels = new ArrayList<>();

        private Builder(Object instructions) {
            this.instructions = Objects.requireNonNull(instructions, "instructions");
        }

        /**
         * Adds the next level, one step above the previous one.
         *
         * @param description a {@code String} or structured content
         * @return this builder
         */
        public Builder level(Object description) {
            levels.add(Objects.requireNonNull(description, "description"));
            return this;
        }

        /**
         * Builds the question from the levels added so far.
         *
         * @return the question
         * @throws IllegalArgumentException if fewer than two levels were added
         */
        public ScoreQuestion build() {
            return new ScoreQuestion(instructions, levels);
        }
    }
}
