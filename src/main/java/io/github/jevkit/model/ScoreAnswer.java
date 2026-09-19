package io.github.jevkit.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The answer to a {@link ScoreQuestion}.
 *
 * <p>The same {@code score} can come from very different answers: 1.0 from a clear peak on level 1, or from a split
 * between levels 0 and 2. Check {@code probabilities} or {@code confidence} before acting on the score alone.
 *
 * @param score         probability-weighted position along the levels, from 0 to {@code levels - 1}; it can fall
 *                      between two levels
 * @param confidence    how certain the model is, from 0 to 1, derived by the API from the shape of
 *                      {@code probabilities}
 * @param legend        each level's description as the API echoed it back, indexed by level
 * @param probabilities each level's probability, indexed by level
 */
public record ScoreAnswer(double score, double confidence, List<String> legend, List<Double> probabilities)
        implements Answer {

    /**
     * Validates the answer and copies {@code legend} and {@code probabilities} into unmodifiable lists.
     *
     * @throws IllegalArgumentException if there are fewer than two levels, {@code legend} and {@code probabilities}
     *                                  differ in size, or a number is out of range
     */
    public ScoreAnswer {
        Objects.requireNonNull(legend, "legend");
        Objects.requireNonNull(probabilities, "probabilities");

        if (probabilities.size() < 2) {
            throw new IllegalArgumentException("A score answer needs at least 2 levels, got " + probabilities.size());
        }

        if (legend.size() != probabilities.size()) {
            throw new IllegalArgumentException("legend has " + legend.size() + " levels but probabilities has "
                    + probabilities.size());
        }

        Probabilities.requireProbability(confidence, "confidence");
        int topLevel = probabilities.size() - 1;

        if (!(score >= 0.0 && score <= topLevel)) {
            throw new IllegalArgumentException("score must be between 0 and " + topLevel + ", got " + score);
        }

        List<Double> probabilitiesCopy = new ArrayList<>(probabilities.size());

        for (int level = 0; level < probabilities.size(); level++) {
            Double probability = Objects.requireNonNull(probabilities.get(level), "probabilities[" + level + "]");
            probabilitiesCopy.add(Probabilities.requireProbability(probability, "probabilities[" + level + "]"));
        }

        List<String> legendCopy = new ArrayList<>(legend.size());

        for (int level = 0; level < legend.size(); level++) {
            legendCopy.add(Objects.requireNonNull(legend.get(level), "legend[" + level + "]"));
        }

        legend = Collections.unmodifiableList(legendCopy);
        probabilities = Collections.unmodifiableList(probabilitiesCopy);
    }

    /**
     * Returns the single most likely level, for when you need one level rather than a position between levels.
     *
     * @return the level with the highest probability; on a tie, the lowest such level
     */
    public int mostLikelyLevel() {
        int best = 0;

        for (int level = 1; level < probabilities.size(); level++) {
            if (probabilities.get(level) > probabilities.get(best)) {
                best = level;
            }
        }

        return best;
    }

    /**
     * {@link #score()} divided by the top level number, so scores from questions with a different number of levels
     * can be combined: a top score is 1.0 whether the question had three levels or ten.
     *
     * @return the score on a 0 to 1 scale
     */
    public double normalizedScore() {
        return score / (probabilities.size() - 1);
    }
}
