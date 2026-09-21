# Changelog

jev-java follows [semantic versioning](https://semver.org). While the version is `0.x`, a minor release (`0.2.0`)
may include breaking changes; they are listed here.

## Unreleased

- Cancelling an asynchronous result now propagates to the underlying HTTP transfer, releasing a stalled response
  instead of leaving it active and potentially blocking client shutdown on Java 21 and later.

## 0.1.0 — 2026-09-19

First release.

- `JevClient` with `evaluate` and `evaluateAsync` for `POST /v1/systemone`, and `listModels` for `GET /v1/models`.
- `ChoiceQuestion`, `ScoreQuestion` and `NoulQuestion`, validated when built, with plain-text or structured content
  (maps, lists and records).
- `QuestionSet` and typed `QuestionKey`s, so `SystemOneResponse.get(key)` returns the right answer type without a
  cast.
- Responses are validated field by field; a missing, mistyped or out-of-range value throws `JevException` naming the
  field instead of becoming a default value.
- `JevException` as the single exception for API and network problems, with the status, request id, error type,
  `Retry-After` delay and invalid field.
- `RetryPolicy` with the same defaults as TypeSafe's official SDKs.
- Configuration through the builder or the `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL` and `TYPESAFE_DEFAULT_MODEL`
  environment variables.
- `SystemOneResponse.builder` for building responses in tests.
- Java 17 or later; works with Gson 2.8.9 and newer.
