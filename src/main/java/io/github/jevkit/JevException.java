package io.github.jevkit;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The only exception jev-java throws for problems with the API or the network, so one {@code catch} covers them all:
 *
 * <ul>
 *   <li>an HTTP error status, after retries: {@link #statusCode()} is present;</li>
 *   <li>a successful status whose body is not valid JSON, or lacks a field the answers need: {@link #statusCode()}
 *       is present and {@link #fieldPath()} names the field, e.g. {@code answers.urgent.noul};</li>
 *   <li>a timeout or connection failure, after retries: {@link #statusCode()} is empty and {@link #getCause()} is the
 *       original {@code IOException}.</li>
 * </ul>
 *
 * <p>Misusing the SDK, such as building a question with one option, throws standard exceptions like
 * {@link IllegalArgumentException} instead. Messages never contain the API key.
 */
public final class JevException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** {@code -1} when there was no HTTP response; kept primitive so the exception stays serializable. */
    private final int statusCode;
    /** The {@code x-typesafe-request-id} header, or {@code null}. */
    private final String requestId;
    /** The error type named in the error body, or {@code null}. */
    private final String errorType;
    /** The delay requested by {@code Retry-After} or {@code retry-after-ms}, or {@code null}. */
    private final Duration retryAfter;
    /** The invalid response field, or {@code null}. */
    private final String fieldPath;

    private JevException(String message, Throwable cause, int statusCode, String requestId, String errorType,
                         Duration retryAfter, String fieldPath) {
        super(message, cause);
        this.statusCode = statusCode;
        this.requestId = requestId;
        this.errorType = errorType;
        this.retryAfter = retryAfter;
        this.fieldPath = fieldPath;
    }

    /** The API answered with an error status. */
    static JevException httpError(int statusCode, String message, String requestId, String errorType,
                                  Duration retryAfter) {
        return new JevException("HTTP " + statusCode + ": " + message + requestSuffix(requestId), null, statusCode,
                requestId, errorType, retryAfter, null);
    }

    /** The API answered with a successful status, but the body cannot be read as the expected answers. */
    static JevException invalidResponse(int statusCode, String fieldPath, String message, String requestId,
                                        Throwable cause) {
        String where = fieldPath == null ? "" : " at " + fieldPath;
        return new JevException("Unexpected response from the API" + where + ": " + message + requestSuffix(requestId),
                cause, statusCode, requestId, null, null, fieldPath);
    }

    /** No HTTP response: timeout, connection failure, or interruption. */
    static JevException noResponse(String message, Throwable cause) {
        return new JevException(message, cause, -1, null, null, null, null);
    }

    private static String requestSuffix(String requestId) {
        return requestId == null ? "" : " (request " + requestId + ")";
    }

    /**
     * Returns the HTTP status of the response that caused the error.
     *
     * @return the status, or empty if no response was received
     */
    public OptionalInt statusCode() {
        return statusCode < 0 ? OptionalInt.empty() : OptionalInt.of(statusCode);
    }

    /**
     * Returns TypeSafe's id for the request, from the {@code x-typesafe-request-id} header. Include it when
     * contacting TypeSafe support.
     *
     * @return the request id, or empty if there was no response or it did not carry one
     */
    public Optional<String> requestId() {
        return Optional.ofNullable(requestId);
    }

    /**
     * Returns the error type the API reported, such as {@code authentication_error}.
     *
     * @return the error type, or empty if the error body did not name one
     */
    public Optional<String> errorType() {
        return Optional.ofNullable(errorType);
    }

    /**
     * Returns how long the API asked to wait before retrying, from {@code Retry-After} or {@code retry-after-ms}.
     * Usually present on 429 and 529 responses.
     *
     * @return the requested delay, or empty if the response did not include one
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Returns the path of the response field that was missing or invalid, such as {@code answers.urgent.noul}.
     *
     * @return the field path, or empty if the error is not about a specific field
     */
    public Optional<String> fieldPath() {
        return Optional.ofNullable(fieldPath);
    }
}
