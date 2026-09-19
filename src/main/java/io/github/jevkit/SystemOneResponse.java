package io.github.jevkit;

import io.github.jevkit.model.Answer;
import io.github.jevkit.model.Usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The answers to one {@link QuestionSet}. Every question in the set has an answer of the right type; the SDK checks
 * that when it reads the response, so {@link #get(QuestionKey)} never finds a missing or mistyped answer.
 *
 * <pre>{@code
 * SystemOneResponse response = client.evaluate(questions);
 * ChoiceAnswer team = response.get(teamKey);
 * NoulAnswer refund = response.get(refundKey);
 * }</pre>
 *
 * <p>To test code that consumes responses without calling the API, build one with {@link #builder(QuestionSet)}.
 */
public final class SystemOneResponse {

    private final QuestionSet questions;
    private final String model;
    private final Usage usage;
    private final String requestId;
    private final Map<String, Answer> answers;

    private SystemOneResponse(Builder builder) {
        this.questions = builder.questions;
        this.model = builder.model;
        this.usage = builder.usage;
        this.requestId = builder.requestId;
        this.answers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.answers));
    }

    /**
     * Starts a response for the given request, mainly for tests of code that consumes responses.
     *
     * @param questions the request being answered
     * @return a builder; set the model, usage, and an answer for every question, then {@link Builder#build()}
     */
    public static Builder builder(QuestionSet questions) {
        return new Builder(questions);
    }

    /**
     * Returns the answer to one question, already typed.
     *
     * @param key the key returned when the question was added
     * @param <A> the answer type
     * @return the answer, never {@code null}
     * @throws IllegalArgumentException if the key belongs to a different request
     */
    @SuppressWarnings("unchecked") // safe: the key's type comes from the question, and every answer was checked against it
    public <A extends Answer> A get(QuestionKey<A> key) {
        Objects.requireNonNull(key, "key");

        if (!questions.contains(key)) {
            throw new IllegalArgumentException(key + " is not part of the request this response answers");
        }

        return (A) answers.get(key.name());
    }

    /**
     * Returns every answer by question name, in the order the questions were added. Prefer {@link #get(QuestionKey)};
     * this is for generic handling such as logging.
     *
     * @return an unmodifiable map from question name to answer
     */
    public Map<String, Answer> answers() {
        return answers;
    }

    /**
     * Returns the request these answers belong to.
     *
     * @return the request
     */
    public QuestionSet questions() {
        return questions;
    }

    /**
     * Returns the versioned model that produced the answers, e.g. {@code jev-1.13.0}, even when the request used an
     * alias such as {@code jev-latest}. Log it to know which model version each result came from.
     *
     * @return the model id
     */
    public String model() {
        return model;
    }

    /**
     * Returns the token usage of the request.
     *
     * @return the usage
     */
    public Usage usage() {
        return usage;
    }

    /**
     * Returns TypeSafe's id for the request, from the {@code x-typesafe-request-id} header. Include it when
     * contacting TypeSafe support.
     *
     * @return the request id, or empty if the response did not carry one
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    @Override
    public String toString() {
        return "SystemOneResponse[model=" + model + ", answers=" + answers + ", usage=" + usage
                + (requestId == null ? "" : ", requestId=" + requestId) + "]";
    }

    /** Collects the answers for a response. Every setter validates immediately. */
    public static final class Builder {

        private final QuestionSet questions;
        private final Map<String, Answer> answers = new LinkedHashMap<>();
        private String model;
        private Usage usage;
        private String requestId;

        private Builder(QuestionSet questions) {
            this.questions = Objects.requireNonNull(questions, "questions");
        }

        /**
         * Sets the model that produced the answers.
         *
         * @param model the model id, e.g. {@code jev-1.13.0}
         * @return this builder
         * @throws IllegalArgumentException if the model is blank
         */
        public Builder model(String model) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank");
            }

            this.model = model;
            return this;
        }

        /**
         * Sets the token usage.
         *
         * @param usage the usage
         * @return this builder
         */
        public Builder usage(Usage usage) {
            this.usage = Objects.requireNonNull(usage, "usage");
            return this;
        }

        /**
         * Sets TypeSafe's request id. Optional.
         *
         * @param requestId the request id, or {@code null} for none
         * @return this builder
         */
        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        /**
         * Sets the answer to one question.
         *
         * @param key    the question's key
         * @param answer the answer; a Choice answer must cover exactly the question's options, and a Score answer
         *               must have one probability per level
         * @param <A>    the answer type
         * @return this builder
         * @throws IllegalArgumentException if the key belongs to another request, the question already has an answer,
         *                                  or the answer does not fit the question
         */
        public <A extends Answer> Builder answer(QuestionKey<A> key, A answer) {
            Objects.requireNonNull(key, "key");

            if (!questions.contains(key)) {
                throw new IllegalArgumentException(key + " is not part of this response's request");
            }

            return put(key, answer);
        }

        /** For the response parser, which looks keys up by name and so only has {@code QuestionKey<?>}. */
        Builder put(QuestionKey<?> key, Answer answer) {
            Objects.requireNonNull(answer, "answer");

            if (answers.containsKey(key.name())) {
                throw new IllegalArgumentException("Question '" + key.name() + "' already has an answer");
            }

            AnswerTypes.requireFits(key.name(), key.question(), answer);
            answers.put(key.name(), answer);
            return this;
        }

        /**
         * Builds the response.
         *
         * @return the response
         * @throws IllegalStateException if the model or usage is missing, or a question has no answer
         */
        public SystemOneResponse build() {
            if (model == null) {
                throw new IllegalStateException("model is required");
            }

            if (usage == null) {
                throw new IllegalStateException("usage is required");
            }

            List<String> unanswered = new ArrayList<>(questions.keys().keySet());
            unanswered.removeAll(answers.keySet());

            if (!unanswered.isEmpty()) {
                throw new IllegalStateException("No answer for " + unanswered);
            }

            return new SystemOneResponse(this);
        }
    }
}
