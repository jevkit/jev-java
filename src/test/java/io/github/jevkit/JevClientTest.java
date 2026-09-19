package io.github.jevkit;

import io.github.jevkit.model.ModelInfo;
import io.github.jevkit.model.NoulAnswer;
import io.github.jevkit.model.NoulQuestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevClientTest {

    private static final String API_KEY = "sk-test-0123456789";
    private static final String NOUL_RESPONSE = "{\"model\":\"jev-1.13.0\",\"answers\":{\"urgent\":{\"type\":\"noul\",\"noul\":0.98}},"
            + "\"usage\":{\"input_tokens\":272,\"output_tokens\":20}}";
    private static final RetryPolicy FAST_RETRIES = RetryPolicy.builder().initialBackoff(Duration.ofMillis(1))
            .maxBackoff(Duration.ofMillis(5)).build();

    private StubServer server;
    private QuestionKey<NoulAnswer> urgent;
    private QuestionSet questions;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubServer();
        QuestionSet.Builder request = QuestionSet.builder("The export button has been greyed out since this morning's update.");
        urgent = request.add("urgent", new NoulQuestion("Is the customer unable to work until this is fixed?"));
        questions = request.build();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private JevClient.Builder client() {
        return JevClient.builder().apiKey(API_KEY).baseUrl(server.baseUrl()).environment(name -> null)
                .timeout(Duration.ofSeconds(5)).retryPolicy(FAST_RETRIES);
    }

    // ---- happy path ----------------------------------------------------------------------------------------------

    @Test
    void sendsTheDocumentedRequestAndReturnsTypedAnswers() {
        server.reply(200, NOUL_RESPONSE, Map.of("x-typesafe-request-id", "req_123"));

        try (JevClient client = client().build()) {
            SystemOneResponse response = client.evaluate(questions);

            assertEquals(0.98, response.get(urgent).value());
            assertEquals(Optional.of("req_123"), response.requestId());
            assertEquals("jev-1.13.0", response.model());
        }

        StubServer.Recorded request = server.recorded().get(0);
        assertEquals("POST", request.method());
        assertEquals("/v1/systemone", request.path());
        assertEquals("Bearer " + API_KEY, request.headers().getFirst("Authorization"));
        assertEquals("application/json", request.headers().getFirst("Content-Type"));
        assertEquals("application/json", request.headers().getFirst("Accept"));
        assertTrue(request.headers().getFirst("User-Agent").startsWith("jev-java/"), request.headers().getFirst("User-Agent"));
        assertNull(request.headers().getFirst("X-TypeSafe-Retry-Count"));
        assertEquals(SystemOneJson.requestBody(questions, "jev-latest"), request.body());
    }

    @Test
    void userAgentCarriesTheBuiltVersion() {
        assertFalse(Version.VALUE.equals("unknown"), "jev-java.properties was not filtered by Maven");
        assertEquals("jev-java/" + Version.VALUE, JevClient.USER_AGENT);
    }

    @Test
    void listsModels() {
        server.reply(200, "{\"models\":[{\"name\":\"jev-latest\",\"description\":\"Flagship\",\"release_date\":\"2026-09-15\"}]}");

        try (JevClient client = client().build()) {
            assertEquals(List.of(new ModelInfo("jev-latest", "Flagship", "2026-09-15")), client.listModels());
        }

        assertEquals("GET", server.recorded().get(0).method());
        assertEquals("/v1/models", server.recorded().get(0).path());
    }

    // ---- errors --------------------------------------------------------------------------------------------------

    @Test
    void clientErrorsAreNotRetriedAndNeverLeakTheKey() {
        server.reply(401, "{\"detail\":{\"error_type\":\"authentication_error\",\"message\":\"Bad key " + API_KEY + "\"}}",
                Map.of("x-typesafe-request-id", "req_401"));

        JevException exception;

        try (JevClient client = client().build()) {
            exception = assertThrows(JevException.class, () -> client.evaluate(questions));
        }

        assertEquals(1, server.recorded().size());
        assertEquals(OptionalInt.of(401), exception.statusCode());
        assertEquals(Optional.of("authentication_error"), exception.errorType());
        assertEquals(Optional.of("req_401"), exception.requestId());
        assertFalse(exception.getMessage().contains(API_KEY), exception.getMessage());
    }

    @Test
    void invalidSuccessfulResponseNamesTheField() {
        server.reply(200, "{\"model\":\"jev-1.13.0\",\"answers\":{\"urgent\":{\"type\":\"noul\"}},"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");

        try (JevClient client = client().build()) {
            JevException exception = assertThrows(JevException.class, () -> client.evaluate(questions));

            assertEquals(Optional.of("answers.urgent.noul"), exception.fieldPath());
            assertEquals(OptionalInt.of(200), exception.statusCode());
        }
    }

    @Test
    void asyncCallsFailOnlyWithJevException() {
        server.reply(422, "{\"detail\":[{\"loc\":[\"body\",\"state\"],\"msg\":\"Field required\"}]}");
        server.reply(422, "{\"detail\":[{\"loc\":[\"body\",\"state\"],\"msg\":\"Field required\"}]}");

        try (JevClient client = client().build()) {
            CompletionException joined = assertThrows(CompletionException.class,
                    () -> client.evaluateAsync(questions).join());
            assertInstanceOf(JevException.class, joined.getCause());

            ExecutionException got = assertThrows(ExecutionException.class,
                    () -> client.evaluateAsync(questions).get(5, TimeUnit.SECONDS));
            assertInstanceOf(JevException.class, got.getCause());
            assertEquals("HTTP 422: state: Field required", got.getCause().getMessage());
        }
    }

    // ---- retries -------------------------------------------------------------------------------------------------

    @Test
    void retriesRateLimitsHonoringRetryAfterAndCountsRetries() {
        server.reply(429, "{\"detail\":\"slow down\"}", Map.of("retry-after-ms", "150"));
        server.reply(200, NOUL_RESPONSE);

        long start = System.nanoTime();

        try (JevClient client = client().build()) {
            assertEquals(0.98, client.evaluate(questions).get(urgent).value());
        }

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis >= 150, "waited only " + elapsedMillis + " ms");
        assertEquals(2, server.recorded().size());
        assertEquals("1", server.recorded().get(1).headers().getFirst("X-TypeSafe-Retry-Count"));
    }

    @Test
    void givesUpAfterMaxRetriesWithTheLastError() {
        for (int i = 0; i < 3; i++) {
            server.reply(529, "{\"detail\":{\"error_type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
                    Map.of("Retry-After", "0"));
        }

        try (JevClient client = client().build()) {
            JevException exception = assertThrows(JevException.class, () -> client.evaluate(questions));

            assertEquals(OptionalInt.of(529), exception.statusCode());
            assertEquals(Optional.of(Duration.ZERO), exception.retryAfter());
            assertEquals(Optional.of("overloaded_error"), exception.errorType());
        }

        assertEquals(3, server.recorded().size());
        assertEquals("2", server.recorded().get(2).headers().getFirst("X-TypeSafe-Retry-Count"));
    }

    @Test
    void noRetriesMeansExactlyOneAttempt() {
        server.reply(503, "busy");

        try (JevClient client = client().retryPolicy(RetryPolicy.none()).build()) {
            assertEquals(OptionalInt.of(503), assertThrows(JevException.class, () -> client.evaluate(questions)).statusCode());
        }

        assertEquals(1, server.recorded().size());
    }

    // ---- no response ---------------------------------------------------------------------------------------------

    @Test
    void timeoutsAreReportedWithTheOriginalCause() {
        server.replyAfter(2_000, 200, NOUL_RESPONSE);
        long start = System.nanoTime();

        try (JevClient client = client().timeout(Duration.ofMillis(200)).retryPolicy(RetryPolicy.none()).build()) {
            JevException exception = assertThrows(JevException.class, () -> client.evaluate(questions));

            assertEquals(OptionalInt.empty(), exception.statusCode());
            assertInstanceOf(HttpTimeoutException.class, exception.getCause());
            assertTrue(exception.getMessage().contains("within 200 ms"), exception.getMessage());
        }

        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500, "did not time out promptly");
    }

    @Test
    void connectionFailuresAreRetriedThenReported() throws IOException {
        int closedPort;

        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        try (JevClient client = client().baseUrl("http://127.0.0.1:" + closedPort).build()) {
            JevException exception = assertThrows(JevException.class, () -> client.evaluate(questions));

            assertEquals(OptionalInt.empty(), exception.statusCode());
            assertInstanceOf(ConnectException.class, exception.getCause());
            assertTrue(exception.getMessage().endsWith("(3 attempts)"), exception.getMessage());
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Test
    void closeFailsCallsWaitingToRetryInsteadOfLeavingThemHanging() throws Exception {
        server.reply(429, "{\"detail\":\"slow down\"}", Map.of("retry-after-ms", "30000"));
        JevClient client = client().build();
        CompletableFuture<SystemOneResponse> call = client.evaluateAsync(questions);

        while (server.recorded().isEmpty()) {
            Thread.sleep(5);
        }

        Thread.sleep(100); // let the 429 arrive and the retry be scheduled
        client.close();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS));
        assertInstanceOf(JevException.class, failure.getCause());
        assertThrows(IllegalStateException.class, () -> client.evaluate(questions));
        client.close(); // idempotent
    }

    @Test
    void cancellingAnAsyncCallStopsPendingRetries() throws Exception {
        server.reply(503, "busy", Map.of("retry-after-ms", "200"));
        server.reply(200, NOUL_RESPONSE);

        try (JevClient client = client().build()) {
            CompletableFuture<SystemOneResponse> call = client.evaluateAsync(questions);

            while (server.recorded().isEmpty()) {
                Thread.sleep(5);
            }

            call.cancel(true);
            Thread.sleep(400);
        }

        assertEquals(1, server.recorded().size());
    }

    // ---- configuration -------------------------------------------------------------------------------------------

    @Test
    void environmentVariablesFillWhatTheBuilderLeavesOut() {
        Map<String, String> environment = Map.of(
                JevClient.API_KEY_ENV, "sk-from-environment",
                JevClient.BASE_URL_ENV, server.baseUrl() + "/",
                JevClient.MODEL_ENV, "jev-1.13.0");

        try (JevClient client = JevClient.builder().environment(environment::get).build()) {
            assertEquals(server.baseUrl(), client.baseUrl());
            assertEquals("jev-1.13.0", client.model());
        }

        try (JevClient client = JevClient.builder().environment(environment::get).model("jev-latest").build()) {
            assertEquals("jev-latest", client.model());
        }

        try (JevClient client = JevClient.builder().apiKey(API_KEY).environment(name -> null).build()) {
            assertEquals(JevClient.DEFAULT_BASE_URL, client.baseUrl());
            assertEquals(JevClient.DEFAULT_MODEL, client.model());
            assertEquals(JevClient.DEFAULT_TIMEOUT, client.timeout());
            assertEquals(RetryPolicy.defaults(), client.retryPolicy());
        }
    }

    @Test
    void theModelCanBeChosenPerRequestAndTheBaseUrlCanHaveAPath() {
        server.reply(200, NOUL_RESPONSE);
        QuestionSet.Builder request = QuestionSet.builder("x").model("jev-1.13.0");
        request.add("urgent", new NoulQuestion("Urgent?"));

        try (JevClient client = client().baseUrl(server.baseUrl() + "/proxy/typesafe/").build()) {
            client.evaluate(request.build());
        }

        assertEquals("/proxy/typesafe/v1/systemone", server.recorded().get(0).path());
        assertTrue(server.recorded().get(0).body().startsWith("{\"model\":\"jev-1.13.0\""));
    }

    @Test
    void rejectsInvalidConfiguration() {
        IllegalStateException noKey = assertThrows(IllegalStateException.class,
                () -> JevClient.builder().environment(name -> null).build());
        assertTrue(noKey.getMessage().contains(JevClient.API_KEY_ENV));

        assertThrows(IllegalArgumentException.class, () -> client().baseUrl("ftp://example.com").build());
        assertThrows(IllegalArgumentException.class, () -> client().baseUrl("https://example.com?x=1").build());
        assertThrows(IllegalArgumentException.class, () -> client().timeout(Duration.ZERO));
    }

    @Test
    void toStringNeverShowsTheKey() {
        try (JevClient client = client().build()) {
            assertFalse(client.toString().contains(API_KEY), client.toString());
        }
    }
}
