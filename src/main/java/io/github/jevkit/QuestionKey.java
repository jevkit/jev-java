package io.github.jevkit;

import io.github.jevkit.model.Answer;
import io.github.jevkit.model.Question;

/**
 * A handle to one question in a {@link QuestionSet}, returned by {@link QuestionSet.Builder#add}. Its type parameter
 * is the answer type, so {@link SystemOneResponse#get(QuestionKey)} returns that type without a cast:
 *
 * <pre>{@code
 * QuestionKey<NoulAnswer> urgent = request.add("urgent", new NoulQuestion("Is it urgent?"));
 * NoulAnswer answer = response.get(urgent);
 * }</pre>
 *
 * <p>Keys can only be obtained from {@code add}, which is what makes the typed lookup safe. A key belongs to the
 * builder that created it: two keys with the same name from different builders are different keys.
 *
 * @param <A> the answer type of the question
 */
public final class QuestionKey<A extends Answer> {

    private final String name;
    private final Question<A> question;
    private final Object owner;

    QuestionKey(String name, Question<A> question, Object owner) {
        this.name = name;
        this.question = question;
        this.owner = owner;
    }

    /**
     * Returns the name the question was added under, which is also its key in the request's JSON.
     *
     * @return the question name
     */
    public String name() {
        return name;
    }

    Question<A> question() {
        return question;
    }

    Object owner() {
        return owner;
    }

    @Override
    public String toString() {
        return "QuestionKey[" + name + ": " + question.getClass().getSimpleName() + "]";
    }
}
