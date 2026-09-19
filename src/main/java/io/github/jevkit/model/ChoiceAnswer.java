package io.github.jevkit.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The answer to a {@link ChoiceQuestion}.
 *
 * @param choice        the option with the highest probability
 * @param confidence    how certain the model is, from 0 to 1, derived by the API from the shape of
 *                      {@code probabilities}; it is not the probability of {@code choice}
 * @param probabilities every option mapped to its probability. Do not rely on the iteration order; the API does not
 *                      keep the order of the question.
 */
public record ChoiceAnswer(String choice, double confidence, Map<String, Double> probabilities) implements Answer {

    /**
     * Validates the answer and copies {@code probabilities} into an unmodifiable map.
     *
     * @param choice        the option with the highest probability
     * @param confidence    how certain the model is, from 0 to 1
     * @param probabilities every option mapped to its probability
     * @throws IllegalArgumentException if {@code choice} is blank or missing from {@code probabilities}, or if any
     *                                  number is not between 0 and 1
     */
    public ChoiceAnswer {
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(probabilities, "probabilities");

        if (choice.isBlank()) {
            throw new IllegalArgumentException("choice must not be blank");
        }

        Probabilities.requireProbability(confidence, "confidence");
        Map<String, Double> copy = new LinkedHashMap<>();

        for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
            String option = Objects.requireNonNull(entry.getKey(), "option name");
            Double probability = Objects.requireNonNull(entry.getValue(), "probabilities." + option);
            copy.put(option, Probabilities.requireProbability(probability, "probabilities." + option));
        }

        if (!copy.containsKey(choice)) {
            throw new IllegalArgumentException("choice '" + choice + "' is not among the options " + copy.keySet());
        }

        probabilities = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the probability the model gave to one option.
     *
     * @param option an option name from the question
     * @return that option's probability
     * @throws IllegalArgumentException if the question had no such option
     */
    public double probability(String option) {
        Double probability = probabilities.get(option);

        if (probability == null) {
            throw new IllegalArgumentException("No option '" + option + "'; options: " + probabilities.keySet());
        }

        return probability;
    }
}
