# jev-java

A Java client for [TypeSafe](https://typesafe.ai)'s System One API and its Jev models: send some state and a set of
typed questions, and get back typed, validated answers.

> **Unofficial.** jev-java is an independent community project. It is not affiliated with, endorsed by, or supported
> by TypeSafe AI, Inc. "TypeSafe" and "Jev" are trademarks of their owner and are used here only to say what this
> library connects to. TypeSafe publishes official SDKs for
> [Python](https://github.com/typesafe-ai/typesafe-sdk-python) and
> [JavaScript](https://github.com/typesafe-ai/typesafe-sdk-js).

- **Typed answers, checked by the compiler.** Adding a question returns a key typed by its answer, so
  `response.get(key)` returns a `ChoiceAnswer`, `ScoreAnswer` or `NoulAnswer` without a cast.
- **Validated responses.** A response with a missing, mistyped or out-of-range answer fails with an exception naming
  the field. It is never turned into a default value.
- **Async first.** `evaluateAsync` never blocks the calling thread, not even between retries.
- **Retries like the official SDKs**, with the same defaults.
- **One small dependency:** Gson.

Requires Java 17 or later. The API is in early access and can change, so jev-java stays on `0.x` versions until it
settles.

## Install

Maven:

```xml
<dependency>
    <groupId>io.github.jevkit</groupId>
    <artifactId>jev-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.jevkit:jev-java:0.1.0")
```

## Quick start

Get an API key from TypeSafe and set it as `TYPESAFE_API_KEY`, or pass it to the builder. Keep it out of source
control.

```java
import io.github.jevkit.JevClient;
import io.github.jevkit.QuestionKey;
import io.github.jevkit.QuestionSet;
import io.github.jevkit.SystemOneResponse;
import io.github.jevkit.model.ChoiceAnswer;
import io.github.jevkit.model.ChoiceQuestion;
import io.github.jevkit.model.NoulAnswer;
import io.github.jevkit.model.NoulQuestion;
import io.github.jevkit.model.ScoreAnswer;
import io.github.jevkit.model.ScoreQuestion;

try (JevClient client = JevClient.builder().build()) {           // reads TYPESAFE_API_KEY
    QuestionSet.Builder request = QuestionSet.builder("The export button has been greyed out since this morning's update.");

    QuestionKey<ChoiceAnswer> team = request.add("team",
            ChoiceQuestion.builder("Where should this ticket go?")
                    .option("billing", "Invoices, charges, plan changes")
                    .option("technical", "Errors, crashes, missing features")
                    .option("other")
                    .build());
    QuestionKey<ScoreAnswer> frustration = request.add("frustration",
            ScoreQuestion.of("How upset does the customer sound?", "Calm", "Annoyed", "Angry"));
    QuestionKey<NoulAnswer> urgent = request.add("urgent",
            new NoulQuestion("Is the customer unable to work until this is fixed?"));

    SystemOneResponse response = client.evaluate(request.build());

    ChoiceAnswer teamAnswer = response.get(team);
    System.out.println(teamAnswer.choice() + " (confidence " + teamAnswer.confidence() + ")");
    System.out.println("Frustration level: " + response.get(frustration).mostLikelyLevel());
    System.out.println("Urgent: " + response.get(urgent).isTrue(0.8));
}
```

All the questions in a `QuestionSet` are sent together and answered in a single request.

## Questions and answers

| Question | Use it for | Answer |
|---|---|---|
| `ChoiceQuestion` | Picking one option from a set you define | `ChoiceAnswer`: `choice()`, `confidence()`, `probabilities()`, `probability(option)` |
| `ScoreQuestion` | Placing the state on ordered levels, lowest first | `ScoreAnswer`: `score()`, `confidence()`, `legend()`, `probabilities()`, `mostLikelyLevel()`, `normalizedScore()` |
| `NoulQuestion` | A yes/no question | `NoulAnswer`: `value()` (probability of yes), `isTrue(threshold)` |

Questions are validated when you build them: a Choice needs at least two distinct options and a Score at least two
levels. See [TypeSafe's documentation](https://docs.typesafe.ai) for how to write good questions.

### Structured content

The state, the instructions, and option and level descriptions can be plain text or structured content: a `Map`, a
`List`, or your own record. Records are converted field by field, in declaration order.

```java
record Ticket(String subject, String body, int previousContacts) {}

QuestionSet.Builder request = QuestionSet.builder(new Ticket("Export", "The export button does nothing on large files", 2));
request.add("repeat", new NoulQuestion("Does `body` say the customer already contacted support?"));
```

Use a `LinkedHashMap` rather than `Map.of(...)` for map-shaped state: `Map.of` does not keep a stable key order, which
makes requests harder to reproduce.

### Models

Requests use `jev-latest` unless you choose another model. That alias moves to new model versions when TypeSafe
releases them, so answers can change without any change on your side. If you tuned thresholds against a version, pin
it:

```java
JevClient client = JevClient.builder().model("jev-1.13.0").build();   // for every request
QuestionSet.builder(state).model("jev-1.13.0");                       // for one request
```

`response.model()` always reports the version that answered, and `client.listModels()` lists the available names.

## Async

```java
client.evaluateAsync(questions)
        .thenAccept(response -> System.out.println("Urgent: " + response.get(urgent).isTrue(0.8)))
        .exceptionally(error -> {
            JevException cause = (JevException) error.getCause();   // error is a CompletionException
            System.err.println("Evaluation failed: " + cause.getMessage());
            return null;
        });
```

The future fails only with a `JevException`. Cancelling it stops any retry that has not started yet.

## Errors

API and network problems are reported as a single unchecked exception, `JevException`:

| Situation | `statusCode()` | Also available |
|---|---|---|
| The API returned an error status (after retries) | the status | `errorType()`, `retryAfter()` |
| A successful status with an invalid or incomplete body | the status | `fieldPath()`, e.g. `answers.urgent.noul` |
| Timeout or connection failure (after retries) | empty | `getCause()`, the original `IOException` |

`requestId()` returns TypeSafe's request id whenever the response carried one; include it if you contact TypeSafe.
Exception messages never contain the API key.

Mistakes in your own code, such as a Choice with a single option or a key from another request, throw standard
exceptions like `IllegalArgumentException`.

## Retries

By default a request is retried up to twice on 408, 429 and 5xx responses, on timeouts, and on connection failures,
backing off from 500 ms to 5 s with jitter and honoring `Retry-After` up to 60 s. These are the same defaults as the
official SDKs. Other statuses, such as 401 or 422, are never retried.

The timeout applies to each attempt. With the defaults, a failing call can take about 35 s in total, so where a caller
is waiting, retry less:

```java
JevClient.builder().retryPolicy(RetryPolicy.none()).timeout(Duration.ofSeconds(3)).build();
JevClient.builder().retryPolicy(RetryPolicy.builder().maxRetries(4).build()).build();
```

## Configuration

| Builder | Environment variable | Default |
|---|---|---|
| `apiKey(String)` | `TYPESAFE_API_KEY` | required |
| `baseUrl(String)` | `TYPESAFE_BASE_URL` | `https://api.typesafe.ai` |
| `model(String)` | `TYPESAFE_DEFAULT_MODEL` | `jev-latest` |
| `timeout(Duration)` | | 10 s per attempt |
| `retryPolicy(RetryPolicy)` | | `RetryPolicy.defaults()` |

Values set on the builder win over environment variables. `baseUrl` may include a path, for example to go through a
proxy.

`JevClient` is thread-safe. Create one, share it, and `close()` it when your application shuts down. It creates no
threads of its own.

## Testing your code

Build responses yourself to test code that consumes them, without calling the API:

```java
SystemOneResponse fake = SystemOneResponse.builder(questions)
        .model("jev-1.13.0")
        .usage(new Usage(100, 10))
        .answer(team, new ChoiceAnswer("billing", 0.9, Map.of("billing", 0.9, "technical", 0.1, "other", 0.0)))
        .answer(frustration, new ScoreAnswer(1.8, 0.7, List.of("Calm", "Annoyed", "Angry"), List.of(0.0, 0.2, 0.8)))
        .answer(urgent, new NoulAnswer(0.95))
        .build();
```

The builder checks the same things as a real response: every question needs an answer that fits it.

## Gson compatibility

jev-java declares a current Gson version but only uses APIs that already exist in Gson 2.8.9, so it also works in
applications where an older Gson is already on the classpath and takes precedence. CI tests it with Gson 2.8.9.

## Building

```bash
./mvnw verify
```

The tests run against a local HTTP server and need no API key. Tests against the real API are excluded from normal
builds, because each run is billed:

```bash
TYPESAFE_API_KEY=... ./mvnw test -Dgroups=live -DexcludedGroups= -Dtest=LiveApiTest -Dsurefire.failIfNoSpecifiedTests=false
```

## License

[MIT](LICENSE)
