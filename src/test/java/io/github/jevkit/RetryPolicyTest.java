package io.github.jevkit;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    @Test
    void defaultsMatchTheOfficialSdks() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(2, policy.maxRetries());
        assertEquals(Duration.ofMillis(500), policy.initialBackoff());
        assertEquals(Duration.ofSeconds(5), policy.maxBackoff());
        assertEquals(0.25, policy.jitter());
        assertEquals(Duration.ofSeconds(60), policy.maxRetryAfter());
        assertTrue(policy.retryTimeouts() && policy.retryConnectionErrors() && policy.respectRetryAfter());
        assertTrue(policy.retriesStatus(408) && policy.retriesStatus(429) && policy.retriesStatus(500)
                && policy.retriesStatus(529) && policy.retriesStatus(599));
        assertFalse(policy.retriesStatus(400) || policy.retriesStatus(401) || policy.retriesStatus(422));
        assertEquals(0, RetryPolicy.none().maxRetries());
    }

    @Test
    void backoffDoublesUpToTheCapAndJitterOnlyShortensIt() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(Duration.ofMillis(500), policy.delayBefore(1, null, () -> 0.0));
        assertEquals(Duration.ofMillis(1000), policy.delayBefore(2, null, () -> 0.0));
        assertEquals(Duration.ofMillis(5000), policy.delayBefore(5, null, () -> 0.0));
        assertEquals(Duration.ofMillis(5000), policy.delayBefore(60, null, () -> 0.0));
        assertEquals(Duration.ofMillis(375), policy.delayBefore(1, null, () -> 1.0));
    }

    @Test
    void honorsTheServerDelayUpToItsCap() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertEquals(Duration.ofSeconds(7), policy.delayBefore(1, Duration.ofSeconds(7), () -> 0.0));
        assertEquals(Duration.ofMillis(500), policy.delayBefore(1, Duration.ofMinutes(5), () -> 0.0));
        assertEquals(Duration.ofMillis(500),
                policy.toBuilder().respectRetryAfter(false).build().delayBefore(1, Duration.ofSeconds(7), () -> 0.0));
    }

    @Test
    void builderStartsFromExistingValuesAndValidates() {
        RetryPolicy custom = RetryPolicy.builder().maxRetries(5).retryStatuses(Set.of(503)).build();

        assertEquals(5, custom.maxRetries());
        assertEquals(Set.of(503), custom.retryStatuses());
        assertEquals(Duration.ofMillis(500), custom.initialBackoff());
        assertEquals(custom, custom.toBuilder().build());
        assertEquals(RetryPolicy.defaults(), RetryPolicy.builder().build());
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().maxRetries(-1));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().jitter(1.5));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().initialBackoff(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.builder().retryStatuses(Set.of(42)));
        assertTrue(RetryPolicy.defaults().toString().contains("408, 429, 5xx"));
    }

    @Test
    void readsRetryAfterInEveryFormat() {
        Instant now = Instant.parse("2026-09-18T12:00:00Z");

        assertEquals(Duration.ofMillis(1500), RetryAfter.from(headers(Map.of("retry-after-ms", "1500", "retry-after", "9")), now));
        assertEquals(Duration.ofSeconds(9), RetryAfter.from(headers(Map.of("Retry-After", "9")), now));
        assertEquals(Duration.ofMillis(2500), RetryAfter.from(headers(Map.of("Retry-After", "2.5")), now));
        assertEquals(Duration.ofSeconds(30),
                RetryAfter.from(headers(Map.of("Retry-After", "Fri, 18 Sep 2026 12:00:30 GMT")), now));
        assertEquals(Duration.ZERO, RetryAfter.from(headers(Map.of("Retry-After", "Fri, 18 Sep 2026 11:00:00 GMT")), now));
        assertNull(RetryAfter.from(headers(Map.of("Retry-After", "soon")), now));
        assertNull(RetryAfter.from(headers(Map.of("Retry-After", "-3")), now));
        assertNull(RetryAfter.from(headers(Map.of()), now));
    }

    private static HttpHeaders headers(Map<String, String> values) {
        Map<String, List<String>> multi = new java.util.HashMap<>();
        values.forEach((name, value) -> multi.put(name, List.of(value)));
        return HttpHeaders.of(multi, (name, value) -> true);
    }
}
