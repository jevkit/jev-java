package io.github.jevkit.model;

/**
 * Token usage reported for one request.
 *
 * @param inputTokens  tokens in the state and questions
 * @param outputTokens tokens in the answers
 */
public record Usage(long inputTokens, long outputTokens) {

    /**
     * Creates a usage report; counts cannot be negative.
     *
     * @throws IllegalArgumentException if a count is negative
     */
    public Usage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token counts must not be negative: input=" + inputTokens
                    + ", output=" + outputTokens);
        }
    }
}
