package io.github.jevkit;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleSupplier;

/**
 * When and how {@link JevClient} retries a failed request. The defaults match TypeSafe's official SDKs:
 *
 * <ul>
 *   <li>up to 2 retries after the first attempt;</li>
 *   <li>on HTTP 408, 429, and every 5xx (which includes 529 Overloaded), on timeouts, and on connection failures;</li>
 *   <li>waiting 500 ms before the first retry, doubling up to 5 s, minus up to 25% at random so many clients don't
 *       retry in lockstep;</li>
 *   <li>honoring the server's {@code Retry-After} or {@code retry-after-ms} when it asks for 60 s or less.</li>
 * </ul>
 *
 * <p>Other statuses, such as 401 or 422, are never retried: repeating the request would not change the result.
 * Retrying is safe because an evaluation has no side effects; each attempt is billed.
 *
 * <p>The timeout applies to each attempt, not to the whole call. With the defaults and a 10 s timeout, a call can take
 * about 35 s before failing. Where that matters, such as a chat message waiting on moderation, use {@link #none()} or a
 * lower {@link Builder#maxRetries(int)}.
 *
 * <pre>{@code
 * RetryPolicy.defaults();
 * RetryPolicy.none();
 * RetryPolicy.builder().maxRetries(4).maxBackoff(Duration.ofSeconds(20)).build();
 * }</pre>
 */
public final class RetryPolicy {

    private static final RetryPolicy DEFAULTS = new Builder().build();

    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double jitter;
    private final Set<Integer> retryStatuses;
    private final boolean retryTimeouts;
    private final boolean retryConnectionErrors;
    private final boolean respectRetryAfter;
    private final Duration maxRetryAfter;

    private RetryPolicy(Builder builder) {
        this.maxRetries = builder.maxRetries;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.jitter = builder.jitter;
        this.retryStatuses = Collections.unmodifiableSet(new TreeSet<>(builder.retryStatuses));
        this.retryTimeouts = builder.retryTimeouts;
        this.retryConnectionErrors = builder.retryConnectionErrors;
        this.respectRetryAfter = builder.respectRetryAfter;
        this.maxRetryAfter = builder.maxRetryAfter;
    }

    /**
     * Returns the default policy, the same as TypeSafe's official SDKs use.
     *
     * @return the default policy
     */
    public static RetryPolicy defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a policy that never retries: every call makes exactly one attempt.
     *
     * @return a policy without retries
     */
    public static RetryPolicy none() {
        return builder().maxRetries(0).build();
    }

    /**
     * Starts a policy from the defaults.
     *
     * @return a builder holding the default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts a policy from this one, to change a few values.
     *
     * @return a builder holding this policy's values
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Returns the number of retries after the first attempt; 0 means no retries.
     *
     * @return the retry count
     */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * Returns the wait before the first retry; each later retry waits twice as long, up to {@link #maxBackoff()}.
     *
     * @return the first backoff delay
     */
    public Duration initialBackoff() {
        return initialBackoff;
    }

    /**
     * Returns the longest wait between retries when the server does not ask for one.
     *
     * @return the backoff cap
     */
    public Duration maxBackoff() {
        return maxBackoff;
    }

    /**
     * Returns the largest fraction randomly taken off each backoff delay, from 0 to 1.
     *
     * @return the jitter fraction
     */
    public double jitter() {
        return jitter;
    }

    /**
     * Returns the HTTP statuses that are retried.
     *
     * @return an unmodifiable, sorted set of statuses
     */
    public Set<Integer> retryStatuses() {
        return retryStatuses;
    }

    /**
     * Returns whether a request that timed out is retried.
     *
     * @return whether timeouts are retried
     */
    public boolean retryTimeouts() {
        return retryTimeouts;
    }

    /**
     * Returns whether a request that could not connect, or lost its connection, is retried.
     *
     * @return whether connection failures are retried
     */
    public boolean retryConnectionErrors() {
        return retryConnectionErrors;
    }

    /**
     * Returns whether the server's {@code Retry-After} or {@code retry-after-ms} replaces the backoff delay.
     *
     * @return whether the server's delay is honored
     */
    public boolean respectRetryAfter() {
        return respectRetryAfter;
    }

    /**
     * Returns the longest server-requested delay that is honored; a longer one falls back to the backoff delay.
     *
     * @return the cap on server-requested delays
     */
    public Duration maxRetryAfter() {
        return maxRetryAfter;
    }

    boolean retriesStatus(int status) {
        return retryStatuses.contains(status);
    }

    /**
     * @param retry      1 for the first retry, 2 for the second, and so on
     * @param retryAfter the server's requested delay, or {@code null}
     * @param random     a source of values in [0, 1), for the jitter
     */
    Duration delayBefore(int retry, Duration retryAfter, DoubleSupplier random) {
        if (respectRetryAfter && retryAfter != null && retryAfter.compareTo(maxRetryAfter) <= 0) {
            return retryAfter;
        }

        long initial = initialBackoff.toMillis();
        long doubled = initial << Math.min(retry - 1, 30);
        long capped = Math.min(doubled < initial ? Long.MAX_VALUE : doubled, maxBackoff.toMillis());
        return Duration.ofMillis(Math.round(capped * (1 - random.getAsDouble() * jitter)));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof RetryPolicy policy)) {
            return false;
        }

        return maxRetries == policy.maxRetries && Double.compare(jitter, policy.jitter) == 0
                && retryTimeouts == policy.retryTimeouts && retryConnectionErrors == policy.retryConnectionErrors
                && respectRetryAfter == policy.respectRetryAfter && initialBackoff.equals(policy.initialBackoff)
                && maxBackoff.equals(policy.maxBackoff) && retryStatuses.equals(policy.retryStatuses)
                && maxRetryAfter.equals(policy.maxRetryAfter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxRetries, initialBackoff, maxBackoff, jitter, retryStatuses, retryTimeouts,
                retryConnectionErrors, respectRetryAfter, maxRetryAfter);
    }

    @Override
    public String toString() {
        return "RetryPolicy[maxRetries=" + maxRetries + ", initialBackoff=" + initialBackoff + ", maxBackoff="
                + maxBackoff + ", jitter=" + jitter + ", retryStatuses=" + describeStatuses() + ", retryTimeouts="
                + retryTimeouts + ", retryConnectionErrors=" + retryConnectionErrors + ", respectRetryAfter="
                + respectRetryAfter + ", maxRetryAfter=" + maxRetryAfter + "]";
    }

    private String describeStatuses() {
        return retryStatuses.equals(Builder.DEFAULT_STATUSES) ? "408, 429, 5xx" : retryStatuses.toString();
    }

    /** Changes values starting from the defaults, or from an existing policy. Every setter validates immediately. */
    public static final class Builder {

        private static final Set<Integer> DEFAULT_STATUSES = defaultStatuses();

        private int maxRetries = 2;
        private Duration initialBackoff = Duration.ofMillis(500);
        private Duration maxBackoff = Duration.ofSeconds(5);
        private double jitter = 0.25;
        private Set<Integer> retryStatuses = DEFAULT_STATUSES;
        private boolean retryTimeouts = true;
        private boolean retryConnectionErrors = true;
        private boolean respectRetryAfter = true;
        private Duration maxRetryAfter = Duration.ofSeconds(60);

        private Builder() {
        }

        private Builder(RetryPolicy policy) {
            maxRetries = policy.maxRetries;
            initialBackoff = policy.initialBackoff;
            maxBackoff = policy.maxBackoff;
            jitter = policy.jitter;
            retryStatuses = policy.retryStatuses;
            retryTimeouts = policy.retryTimeouts;
            retryConnectionErrors = policy.retryConnectionErrors;
            respectRetryAfter = policy.respectRetryAfter;
            maxRetryAfter = policy.maxRetryAfter;
        }

        private static Set<Integer> defaultStatuses() {
            Set<Integer> statuses = new TreeSet<>(Set.of(408, 429));

            for (int status = 500; status <= 599; status++) {
                statuses.add(status);
            }

            return Collections.unmodifiableSet(statuses);
        }

        /**
         * Sets the number of retries after the first attempt.
         *
         * @param maxRetries 0 or more; 0 disables retries
         * @return this builder
         * @throws IllegalArgumentException if negative
         */
        public Builder maxRetries(int maxRetries) {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be zero or more, got " + maxRetries);
            }

            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Sets the wait before the first retry.
         *
         * @param initialBackoff a positive duration
         * @return this builder
         * @throws IllegalArgumentException if zero or negative
         */
        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = requirePositive(initialBackoff, "initialBackoff");
            return this;
        }

        /**
         * Sets the longest wait between retries when the server does not ask for one.
         *
         * @param maxBackoff a positive duration
         * @return this builder
         * @throws IllegalArgumentException if zero or negative
         */
        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = requirePositive(maxBackoff, "maxBackoff");
            return this;
        }

        /**
         * Sets the largest fraction randomly taken off each backoff delay.
         *
         * @param jitter from 0 (no randomness) to 1
         * @return this builder
         * @throws IllegalArgumentException if outside 0 to 1
         */
        public Builder jitter(double jitter) {
            if (!(jitter >= 0 && jitter <= 1)) {
                throw new IllegalArgumentException("jitter must be between 0 and 1, got " + jitter);
            }

            this.jitter = jitter;
            return this;
        }

        /**
         * Sets the HTTP statuses that are retried, replacing the defaults (408, 429, and 500 to 599).
         *
         * @param retryStatuses statuses from 100 to 599; may be empty
         * @return this builder
         * @throws IllegalArgumentException if a status is outside 100 to 599
         */
        public Builder retryStatuses(Set<Integer> retryStatuses) {
            for (Integer status : Objects.requireNonNull(retryStatuses, "retryStatuses")) {
                if (status == null || status < 100 || status > 599) {
                    throw new IllegalArgumentException("Not an HTTP status: " + status);
                }
            }

            this.retryStatuses = Set.copyOf(retryStatuses);
            return this;
        }

        /**
         * Sets whether a request that timed out is retried.
         *
         * @param retryTimeouts whether to retry timeouts
         * @return this builder
         */
        public Builder retryTimeouts(boolean retryTimeouts) {
            this.retryTimeouts = retryTimeouts;
            return this;
        }

        /**
         * Sets whether a request that could not connect, or lost its connection, is retried.
         *
         * @param retryConnectionErrors whether to retry connection failures
         * @return this builder
         */
        public Builder retryConnectionErrors(boolean retryConnectionErrors) {
            this.retryConnectionErrors = retryConnectionErrors;
            return this;
        }

        /**
         * Sets whether the server's {@code Retry-After} or {@code retry-after-ms} replaces the backoff delay.
         *
         * @param respectRetryAfter whether to honor the server's delay
         * @return this builder
         */
        public Builder respectRetryAfter(boolean respectRetryAfter) {
            this.respectRetryAfter = respectRetryAfter;
            return this;
        }

        /**
         * Sets the longest server-requested delay that is honored.
         *
         * @param maxRetryAfter a positive duration
         * @return this builder
         * @throws IllegalArgumentException if zero or negative
         */
        public Builder maxRetryAfter(Duration maxRetryAfter) {
            this.maxRetryAfter = requirePositive(maxRetryAfter, "maxRetryAfter");
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return the policy
         */
        public RetryPolicy build() {
            return new RetryPolicy(this);
        }

        private static Duration requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);

            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive, got " + duration);
            }

            return duration;
        }
    }
}
