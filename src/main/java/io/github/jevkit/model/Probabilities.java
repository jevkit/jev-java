package io.github.jevkit.model;

/** Range checks shared by the answer records. */
final class Probabilities {

    private Probabilities() {
    }

    static double requireProbability(double value, String name) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, got " + value);
        }

        return value;
    }
}
