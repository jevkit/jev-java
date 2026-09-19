package io.github.jevkit;

import io.github.jevkit.internal.Content;
import io.github.jevkit.model.Answer;
import io.github.jevkit.model.Question;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One request: the state to evaluate and the questions to ask about it, all answered in a single call.
 *
 * <pre>{@code
 * QuestionSet.Builder request = QuestionSet.builder("The app logs me out every few minutes.");
 * QuestionKey<ChoiceAnswer> team = request.add("team", ChoiceQuestion.of("Where should this go?", "billing", "technical"));
 * QuestionKey<NoulAnswer> login = request.add("login", new NoulQuestion("Is this about signing in?"));
 * QuestionSet questions = request.build();
 * }</pre>
 *
 * <p>Questions in one set are independent: no answer is context for another. Asking several questions in one set is
 * cheaper and faster than one request per question, because the state is read once.
 */
public final class QuestionSet {

    private final Object state;
    private final String model;
    private final Map<String, QuestionKey<?>> keys;
    private final Object owner;

    private QuestionSet(Builder builder) {
        this.state = builder.state;
        this.model = builder.model;
        this.keys = Collections.unmodifiableMap(new LinkedHashMap<>(builder.keys));
        this.owner = builder.owner;
    }

    /**
     * Starts a request about the given state.
     *
     * @param state what to evaluate: a {@code String}, or structured content ({@code Map}, {@code List}, or a record)
     *              that questions can point into, e.g. {@code `ticket.body`}
     * @return a builder; add at least one question, then {@link Builder#build()}
     * @throws IllegalArgumentException if the state cannot be represented as JSON
     */
    public static Builder builder(Object state) {
        return new Builder(state);
    }

    /**
     * Returns the state as it will be sent: a {@code String}, or an unmodifiable copy of the structured content.
     *
     * @return the state, never {@code null}
     */
    public Object state() {
        return state;
    }

    /**
     * Returns the model chosen for this request only, if any; otherwise the client's model is used.
     *
     * @return the model for this request, or empty for the client's default
     */
    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    /**
     * Returns the questions by name, in the order they were added.
     *
     * @return an unmodifiable map from question name to question
     */
    public Map<String, Question<?>> questions() {
        Map<String, Question<?>> questions = new LinkedHashMap<>();
        keys.forEach((name, key) -> questions.put(name, key.question()));
        return Collections.unmodifiableMap(questions);
    }

    /** Whether {@code key} was added to this set, as opposed to another set or a later use of the same builder. */
    boolean contains(QuestionKey<?> key) {
        return key.owner() == owner && keys.get(key.name()) == key;
    }

    Map<String, QuestionKey<?>> keys() {
        return keys;
    }

    @Override
    public String toString() {
        return "QuestionSet" + keys.values() + (model == null ? "" : " model=" + model);
    }

    /** Adds questions one by one; each {@link #add} returns the typed key used to read that question's answer. */
    public static final class Builder {

        private final Object state;
        private final Map<String, QuestionKey<?>> keys = new LinkedHashMap<>();
        private final Object owner = new Object();
        private String model;

        private Builder(Object state) {
            this.state = Content.copyOf(Objects.requireNonNull(state, "state"), "state");
        }

        /**
         * Adds a question and returns the key to read its answer with.
         *
         * @param name     a name for the question, unique in this request; it is sent as the question's key but the
         *                 model never sees it
         * @param question the question
         * @param <A>      the answer type of the question
         * @return the typed key for {@link SystemOneResponse#get(QuestionKey)}
         * @throws IllegalArgumentException if the name is blank or already used in this request
         */
        public <A extends Answer> QuestionKey<A> add(String name, Question<A> question) {
            Objects.requireNonNull(question, "question");

            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Question names must not be blank");
            }

            if (keys.containsKey(name)) {
                throw new IllegalArgumentException("A question named '" + name + "' was already added");
            }

            QuestionKey<A> key = new QuestionKey<>(name, question, owner);
            keys.put(name, key);
            return key;
        }

        /**
         * Uses a different model for this request only, e.g. a pinned version such as {@code jev-1.13.0} while the
         * client defaults to {@code jev-latest}.
         *
         * @param model the model name
         * @return this builder
         * @throws IllegalArgumentException if the name is blank
         */
        public Builder model(String model) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank");
            }

            this.model = model;
            return this;
        }

        /**
         * Builds the request from the questions added so far. The builder can keep adding questions afterwards; keys
         * added later do not belong to sets that were already built.
         *
         * @return the request
         * @throws IllegalStateException if no question was added
         */
        public QuestionSet build() {
            if (keys.isEmpty()) {
                throw new IllegalStateException("A request needs at least one question");
            }

            return new QuestionSet(this);
        }
    }
}
