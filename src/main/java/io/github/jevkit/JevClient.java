package io.github.jevkit;

import io.github.jevkit.model.ModelInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * Client for TypeSafe's System One API. Create one, reuse it everywhere (it is thread-safe), and close it when your
 * application shuts down.
 *
 * <pre>{@code
 * try (JevClient client = JevClient.builder().apiKey(apiKey).build()) {
 *     QuestionSet.Builder request = QuestionSet.builder("The app logs me out every few minutes.");
 *     QuestionKey<NoulAnswer> login = request.add("login", new NoulQuestion("Is this about signing in?"));
 *
 *     SystemOneResponse response = client.evaluate(request.build());
 *     boolean aboutLogin = response.get(login).isTrue(0.7);
 * }
 * }</pre>
 *
 * <p>{@link #evaluateAsync} never blocks the calling thread, including while it waits between retries;
 * {@link #evaluate} waits for it. Both report API and network problems as {@link JevException} only.
 *
 * <p>The client creates no threads of its own. It uses {@link HttpClient}'s threads for I/O and the common pool for
 * the delay between retries.
 */
public final class JevClient implements AutoCloseable {

    /** The API root used when neither {@link Builder#baseUrl} nor {@value #BASE_URL_ENV} is set. */
    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";

    /** The model used when neither {@link Builder#model} nor {@value #MODEL_ENV} is set. */
    public static final String DEFAULT_MODEL = "jev-latest";

    /** The per-attempt timeout used when {@link Builder#timeout} is not set, the same as the official SDKs. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Environment variable read for the API key when {@link Builder#apiKey} is not set. */
    public static final String API_KEY_ENV = "TYPESAFE_API_KEY";

    /** Environment variable read for the API root when {@link Builder#baseUrl} is not set. */
    public static final String BASE_URL_ENV = "TYPESAFE_BASE_URL";

    /** Environment variable read for the default model when {@link Builder#model} is not set. */
    public static final String MODEL_ENV = "TYPESAFE_DEFAULT_MODEL";

    static final String SYSTEM_ONE_PATH = "/v1/systemone";
    static final String MODELS_PATH = "/v1/models";
    static final String REQUEST_ID_HEADER = "x-typesafe-request-id";
    static final String RETRY_COUNT_HEADER = "X-TypeSafe-Retry-Count";
    static final String USER_AGENT = "jev-java/" + Version.VALUE;

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final URI systemOneUri;
    private final URI modelsUri;
    private final String model;
    private final Duration timeout;
    private final RetryPolicy retryPolicy;
    private final DoubleSupplier random;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Calls waiting for their next retry, failed at once by {@link #close()} instead of when their timer fires. */
    private final Set<CompletableFuture<?>> awaitingRetry = ConcurrentHashMap.newKeySet();

    private JevClient(String apiKey, String baseUrl, String model, Duration timeout, RetryPolicy retryPolicy,
                      DoubleSupplier random) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.systemOneUri = URI.create(baseUrl + SYSTEM_ONE_PATH);
        this.modelsUri = URI.create(baseUrl + MODELS_PATH);
        this.model = model;
        this.timeout = timeout;
        this.retryPolicy = retryPolicy;
        this.random = random;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Starts configuring a client. Only the API key is required, and it can come from {@value #API_KEY_ENV}.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Asks every question in the request, in one call, and waits for the answers.
     *
     * @param questions the state and questions
     * @return the answers, all present and validated
     * @throws JevException          if the API returns an error, the response is invalid, or the API cannot be
     *                               reached, after retries
     * @throws IllegalStateException if the client is closed
     */
    public SystemOneResponse evaluate(QuestionSet questions) {
        return await(evaluateAsync(questions));
    }

    /**
     * Asks every question in the request, in one call, without blocking the calling thread.
     *
     * <p>The returned future fails only with a {@link JevException}; {@code join()} reports it wrapped in a
     * {@link CompletionException}, and {@code get()} in an {@link ExecutionException}. Cancelling the future stops
     * any retries that have not started.
     *
     * @param questions the state and questions
     * @return a future completed with the answers
     * @throws IllegalStateException if the client is closed
     */
    public CompletableFuture<SystemOneResponse> evaluateAsync(QuestionSet questions) {
        Objects.requireNonNull(questions, "questions");
        ensureOpen();
        HttpRequest.Builder request = request(systemOneUri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(SystemOneJson.requestBody(questions, model),
                        StandardCharsets.UTF_8));
        return call(request, (status, body, requestId) -> SystemOneJson.readResponse(questions, status, body, requestId));
    }

    /**
     * Lists the model names the account can use, such as {@code jev-latest}. Versioned ids like {@code jev-1.13.0}
     * are accepted by requests even when not listed.
     *
     * @return the models
     * @throws JevException          if the API returns an error or cannot be reached, after retries
     * @throws IllegalStateException if the client is closed
     */
    public List<ModelInfo> listModels() {
        return await(listModelsAsync());
    }

    /**
     * Lists the model names the account can use, without blocking the calling thread.
     *
     * @return a future completed with the models; it fails only with a {@link JevException}
     * @throws IllegalStateException if the client is closed
     */
    public CompletableFuture<List<ModelInfo>> listModelsAsync() {
        ensureOpen();
        return call(request(modelsUri).GET(), SystemOneJson::readModels);
    }

    /**
     * Returns the model used by requests that don't choose one with {@link QuestionSet.Builder#model}.
     *
     * @return the default model
     */
    public String model() {
        return model;
    }

    /**
     * Returns the API root the client sends requests to.
     *
     * @return the base URL, without a trailing slash
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Returns the timeout applied to each attempt.
     *
     * @return the per-attempt timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the retry policy.
     *
     * @return the retry policy
     */
    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /**
     * Closes the client. Calls waiting for a retry fail at once with a {@link JevException}; later calls throw
     * {@link IllegalStateException}. On Java 21 and later this also closes the underlying {@link HttpClient}, which
     * waits for requests already in flight. Calling it again has no effect.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        for (CompletableFuture<?> call : awaitingRetry) {
            call.completeExceptionally(JevException.noResponse("The JevClient was closed while waiting to retry", null));
        }

        awaitingRetry.clear();

        // HttpClient implements AutoCloseable from Java 21 on; this library compiles for Java 17.
        if (http instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Nothing useful to do while shutting down.
            }
        }
    }

    @Override
    public String toString() {
        return "JevClient[baseUrl=" + baseUrl + ", model=" + model + ", timeout=" + timeout + ", retryPolicy="
                + retryPolicy + "]";
    }

    // ---- calls ---------------------------------------------------------------------------------------------------

    /** Turns a response into the call's result, or throws {@link JevException}. */
    @FunctionalInterface
    private interface ResponseReader<T> {
        T read(int status, String body, String requestId);
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
    }

    private <T> CompletableFuture<T> call(HttpRequest.Builder request, ResponseReader<T> reader) {
        CompletableFuture<T> result = new CompletableFuture<>();
        attempt(request, reader, 0, result);
        return result;
    }

    private <T> void attempt(HttpRequest.Builder template, ResponseReader<T> reader, int retry, CompletableFuture<T> result) {
        if (result.isDone()) {
            return; // cancelled by the caller, or failed by close()
        }

        if (closed.get()) {
            result.completeExceptionally(JevException.noResponse("The JevClient was closed while waiting to retry", null));
            return;
        }

        HttpRequest request = retry == 0
                ? template.build()
                : template.copy().header(RETRY_COUNT_HEADER, Integer.toString(retry)).build();
        CompletableFuture<HttpResponse<String>> sent;

        try {
            sent = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            result.completeExceptionally(JevException.noResponse("Could not send the request: " + e, e));
            return;
        }

        sent.whenComplete((response, error) -> {
            try {
                if (error != null) {
                    onNoResponse(unwrap(error), template, reader, retry, result);
                } else {
                    onResponse(response, template, reader, retry, result);
                }
            } catch (Throwable unexpected) {
                result.completeExceptionally(unexpected);
            }
        });
    }

    private <T> void onResponse(HttpResponse<String> response, HttpRequest.Builder template, ResponseReader<T> reader,
                                int retry, CompletableFuture<T> result) {
        int status = response.statusCode();
        String requestId = response.headers().firstValue(REQUEST_ID_HEADER).orElse(null);

        if (status >= 200 && status < 300) {
            try {
                result.complete(reader.read(status, response.body(), requestId));
            } catch (JevException e) {
                result.completeExceptionally(e);
            }

            return;
        }

        Duration retryAfter = RetryAfter.from(response.headers(), Instant.now());

        if (retryPolicy.retriesStatus(status) && retry < retryPolicy.maxRetries()) {
            retryLater(template, reader, retry, result, retryAfter);
            return;
        }

        result.completeExceptionally(SystemOneJson.readError(status, response.body(), requestId, retryAfter, apiKey));
    }

    private <T> void onNoResponse(Throwable error, HttpRequest.Builder template, ResponseReader<T> reader, int retry,
                                  CompletableFuture<T> result) {
        boolean timedOut = error instanceof HttpTimeoutException;
        boolean retryable = timedOut ? retryPolicy.retryTimeouts()
                : error instanceof IOException && retryPolicy.retryConnectionErrors();

        if (retryable && retry < retryPolicy.maxRetries()) {
            retryLater(template, reader, retry, result, null);
            return;
        }

        String attempts = retry == 0 ? "" : " (" + (retry + 1) + " attempts)";
        String message = timedOut
                ? "No response from " + baseUrl + " within " + timeout.toMillis() + " ms" + attempts
                : "Could not reach " + baseUrl + ": " + error + attempts;
        result.completeExceptionally(JevException.noResponse(message, error));
    }

    private <T> void retryLater(HttpRequest.Builder template, ResponseReader<T> reader, int retry,
                                CompletableFuture<T> result, Duration retryAfter) {
        Duration delay = retryPolicy.delayBefore(retry + 1, retryAfter, random);
        awaitingRetry.add(result);

        if (closed.get()) { // close() may have run before the add; make sure nothing is left waiting
            awaitingRetry.remove(result);
            result.completeExceptionally(JevException.noResponse("The JevClient was closed while waiting to retry", null));
            return;
        }

        CompletableFuture.runAsync(() -> {
            awaitingRetry.remove(result);
            attempt(template, reader, retry + 1, result);
        }, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS));
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("This JevClient is closed");
        }
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }

        return error;
    }

    /** Waits for a call and rethrows its {@link JevException} as is, never wrapped. */
    private static <T> T await(CompletableFuture<T> call) {
        try {
            return call.get();
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e);

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            if (cause instanceof Error fatal) {
                throw fatal;
            }

            throw JevException.noResponse("The request failed: " + cause, cause);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw JevException.noResponse("Interrupted while waiting for the API", e);
        }
    }

    // ---- configuration -------------------------------------------------------------------------------------------

    /**
     * Configures a {@link JevClient}. Values set here win over environment variables, which win over the defaults.
     */
    public static final class Builder {

        private String apiKey;
        private String baseUrl;
        private String model;
        private Duration timeout = DEFAULT_TIMEOUT;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private Function<String, String> environment = System::getenv;
        private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

        private Builder() {
        }

        /**
         * Sets the API key. Without it, {@value #API_KEY_ENV} is used. Keep the key out of source control.
         *
         * @param apiKey the API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the API root, for example to point a test at a local server. Without it, {@value #BASE_URL_ENV} is
         * used, then {@value #DEFAULT_BASE_URL}.
         *
         * @param baseUrl an {@code http} or {@code https} URL such as {@code https://api.typesafe.ai}; a path is kept,
         *                for a proxy such as {@code https://proxy.example.com/typesafe}, and the client appends
         *                {@code /v1/systemone} to it
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the model for requests that don't choose one. Without it, {@value #MODEL_ENV} is used, then
         * {@value #DEFAULT_MODEL}.
         *
         * <p>An alias such as {@code jev-latest} moves to new model versions as TypeSafe releases them, so answers
         * can change without any change on your side. If you tuned confidence thresholds, pin a version such as
         * {@code jev-1.13.0} instead.
         *
         * @param model the model name
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the time allowed for each attempt, including connecting. Retries each get their own timeout.
         *
         * @param timeout a positive duration; the default is 10 seconds
         * @return this builder
         * @throws IllegalArgumentException if zero or negative
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");

            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive, got " + timeout);
            }

            this.timeout = timeout;
            return this;
        }

        /**
         * Sets when and how failed requests are retried.
         *
         * @param retryPolicy the policy; the default is {@link RetryPolicy#defaults()}
         * @return this builder
         */
        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        /** For tests: where environment variables are read from. */
        Builder environment(Function<String, String> environment) {
            this.environment = environment;
            return this;
        }

        /** For tests: the source of backoff jitter. */
        Builder random(DoubleSupplier random) {
            this.random = random;
            return this;
        }

        /**
         * Builds the client.
         *
         * @return the client
         * @throws IllegalStateException    if no API key was set and {@value #API_KEY_ENV} is empty
         * @throws IllegalArgumentException if the base URL is not a valid {@code http} or {@code https} URL
         */
        public JevClient build() {
            String resolvedKey = resolve(apiKey, API_KEY_ENV, null);

            if (resolvedKey == null) {
                throw new IllegalStateException("No API key: call apiKey(...) or set the " + API_KEY_ENV
                        + " environment variable");
            }

            String resolvedUrl = normalizeBaseUrl(resolve(baseUrl, BASE_URL_ENV, DEFAULT_BASE_URL));
            String resolvedModel = resolve(model, MODEL_ENV, DEFAULT_MODEL);
            return new JevClient(resolvedKey, resolvedUrl, resolvedModel, timeout, retryPolicy, random);
        }

        private String resolve(String explicit, String variable, String fallback) {
            if (explicit != null && !explicit.isBlank()) {
                return explicit.trim();
            }

            String fromEnvironment = environment.apply(variable);
            return fromEnvironment != null && !fromEnvironment.isBlank() ? fromEnvironment.trim() : fallback;
        }

        private static String normalizeBaseUrl(String url) {
            String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            URI uri;

            try {
                uri = new URI(trimmed);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid base URL: " + url, e);
            }

            boolean isHttp = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());

            if (!isHttp || uri.getHost() == null) {
                throw new IllegalArgumentException("The base URL must be an http or https URL with a host, got " + url);
            }

            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("The base URL must not have a query or fragment, got " + url);
            }

            return trimmed;
        }
    }
}
