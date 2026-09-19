package io.github.jevkit.model;

import java.util.Objects;

/**
 * One name the account can send as the request's model, as listed by {@code GET /v1/models}.
 *
 * @param name        a model id such as {@code jev-1.13.0} or an alias such as {@code jev-latest}
 * @param description what the model or alias is for
 * @param releaseDate when it was released, as the API reports it
 */
public record ModelInfo(String name, String description, String releaseDate) {

    /**
     * Creates a model entry; every field is required.
     *
     * @param name        the model id or alias
     * @param description what the model or alias is for
     * @param releaseDate when it was released, as the API reports it
     * @throws NullPointerException if any field is {@code null}
     */
    public ModelInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(releaseDate, "releaseDate");
    }
}
