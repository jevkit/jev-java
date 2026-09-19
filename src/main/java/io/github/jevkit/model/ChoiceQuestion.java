package io.github.jevkit.model;

import io.github.jevkit.internal.Content;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Chooses one of the options you list. The answer names the chosen option and gives a probability for each one.
 *
 * <pre>{@code
 * ChoiceQuestion.of("Where should this ticket go?", "billing", "technical", "sales");
 *
 * ChoiceQuestion.builder("Where should this ticket go?")
 *     .option("billing", "Invoices, charges, plan changes")
 *     .option("technical", "Errors, crashes, missing features")
 *     .option("other")                                      // no description
 *     .build();
 * }</pre>
 *
 * @param instructions what the model should decide: a {@code String} or structured content
 * @param options      option name to description, in the order given; a description may be {@code null} when the
 *                     name speaks for itself. At least two options.
 */
public record ChoiceQuestion(Object instructions, Map<String, Object> options) implements Question<ChoiceAnswer> {

    /**
     * Validates the question and copies its content into unmodifiable structures.
     *
     * @throws IllegalArgumentException if there are fewer than two options, a name is blank, or any content cannot be
     *                                  represented as JSON
     */
    public ChoiceQuestion {
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(options, "options");
        instructions = Content.copyOf(instructions, "instructions");

        if (options.size() < 2) {
            throw new IllegalArgumentException("A choice question needs at least 2 options, got " + options.size());
        }

        Map<String, Object> copy = new LinkedHashMap<>();

        for (Map.Entry<String, Object> option : options.entrySet()) {
            String name = requireName(option.getKey());
            copy.put(name, Content.copyOf(option.getValue(), "options." + name));
        }

        options = Collections.unmodifiableMap(copy);
    }

    /**
     * A question whose options need no description.
     *
     * @param instructions what the model should decide
     * @param options      at least two distinct option names
     * @return the question
     */
    public static ChoiceQuestion of(Object instructions, String... options) {
        Builder builder = builder(instructions);

        for (String option : options) {
            builder.option(option);
        }

        return builder.build();
    }

    /**
     * Starts a question whose options are added one by one.
     *
     * @param instructions what the model should decide: a {@code String} or structured content
     * @return a builder; add at least two options, then {@link Builder#build()}
     */
    public static Builder builder(Object instructions) {
        return new Builder(instructions);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Option names must not be blank");
        }

        return name;
    }

    /** Collects options in order and rejects duplicates as they are added. */
    public static final class Builder {

        private final Object instructions;
        private final Map<String, Object> options = new LinkedHashMap<>();

        private Builder(Object instructions) {
            this.instructions = Objects.requireNonNull(instructions, "instructions");
        }

        /**
         * Adds an option without a description.
         *
         * @param name the option name, which is also what {@link ChoiceAnswer#choice()} returns
         * @return this builder
         * @throws IllegalArgumentException if the name is blank or already added
         */
        public Builder option(String name) {
            return option(name, null);
        }

        /**
         * Adds an option with a description that tells it apart from the others.
         *
         * @param name        the option name, which is also what {@link ChoiceAnswer#choice()} returns
         * @param description a {@code String}, structured content, or {@code null}
         * @return this builder
         * @throws IllegalArgumentException if the name is blank or already added
         */
        public Builder option(String name, Object description) {
            requireName(name);

            if (options.containsKey(name)) {
                throw new IllegalArgumentException("Option '" + name + "' was already added");
            }

            options.put(name, description);
            return this;
        }

        /**
         * Builds the question from the options added so far.
         *
         * @return the question
         * @throws IllegalArgumentException if fewer than two options were added
         */
        public ChoiceQuestion build() {
            return new ChoiceQuestion(instructions, options);
        }
    }
}
