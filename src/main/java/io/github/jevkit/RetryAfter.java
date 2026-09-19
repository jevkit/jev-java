package io.github.jevkit;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Reads the delay a response asks for: {@code retry-after-ms} first, then {@code Retry-After} in seconds or as a date. */
final class RetryAfter {

    private RetryAfter() {
    }

    /** @return the requested delay, or {@code null} if the headers don't carry a valid one */
    static Duration from(HttpHeaders headers, Instant now) {
        Optional<Duration> millis = headers.firstValue("retry-after-ms").flatMap(RetryAfter::nonNegativeNumber)
                .map(value -> Duration.ofMillis(Math.round(value)));

        if (millis.isPresent()) {
            return millis.get();
        }

        Optional<String> header = headers.firstValue("retry-after");

        if (header.isEmpty()) {
            return null;
        }

        Optional<Duration> seconds = nonNegativeNumber(header.get()).map(value -> Duration.ofMillis(Math.round(value * 1000)));

        if (seconds.isPresent()) {
            return seconds.get();
        }

        try {
            Instant at = ZonedDateTime.parse(header.get().trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration until = Duration.between(now, at);
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Optional<Double> nonNegativeNumber(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            return value >= 0 && !Double.isInfinite(value) ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
