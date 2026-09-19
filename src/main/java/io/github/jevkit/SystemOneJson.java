package io.github.jevkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.jevkit.model.Answer;
import io.github.jevkit.model.ChoiceAnswer;
import io.github.jevkit.model.ChoiceQuestion;
import io.github.jevkit.model.ModelInfo;
import io.github.jevkit.model.NoulAnswer;
import io.github.jevkit.model.NoulQuestion;
import io.github.jevkit.model.Question;
import io.github.jevkit.model.ScoreAnswer;
import io.github.jevkit.model.ScoreQuestion;
import io.github.jevkit.model.Usage;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds request bodies and reads response bodies. Kept apart from {@code JevClient} so serialization never mixes with
 * networking, and package-private so Gson stays out of the public API.
 *
 * <p>Only uses Gson APIs that exist in Gson 2.8.9, so it works where an older Gson is already on the classpath.
 */
final class SystemOneJson {

    /** Keeps {@code null} option descriptions, which the API accepts, and leaves {@code <} and {@code >} readable. */
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private static final int MAX_ERROR_MESSAGE = 500;
    private static final int MIN_MASKED_KEY_LENGTH = 8;
    private static final String REDACTED = "[REDACTED]";

    private SystemOneJson() {
    }

    // ---- request -------------------------------------------------------------------------------------------------

    static String requestBody(QuestionSet request, String defaultModel) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model().orElse(defaultModel));
        body.add("state", toJson(request.state()));

        JsonObject questions = new JsonObject();
        request.questions().forEach((name, question) -> questions.add(name, questionJson(question)));
        body.add("questions", questions);

        return GSON.toJson(body);
    }

    private static JsonObject questionJson(Question<?> question) {
        JsonObject json = new JsonObject();

        if (question instanceof ChoiceQuestion choice) {
            json.addProperty("type", "choice");
            json.add("instructions", toJson(choice.instructions()));
            json.add("criteria", toJson(choice.options()));
        } else if (question instanceof ScoreQuestion score) {
            json.addProperty("type", "score");
            json.add("instructions", toJson(score.instructions()));
            json.add("criteria", toJson(score.levels()));
        } else if (question instanceof NoulQuestion noul) {
            json.addProperty("type", "noul");
            json.add("instructions", toJson(noul.instructions()));

            if (noul.whenTrue() != null || noul.whenFalse() != null) {
                JsonObject criteria = new JsonObject();

                if (noul.whenTrue() != null) {
                    criteria.add("true", toJson(noul.whenTrue()));
                }

                if (noul.whenFalse() != null) {
                    criteria.add("false", toJson(noul.whenFalse()));
                }

                json.add("criteria", criteria);
            }
        } else {
            throw new AssertionError("Unhandled question type " + question.getClass().getName());
        }

        return json;
    }

    /** Converts content already normalized by {@code Content.copyOf}: strings, numbers, booleans, null, maps, lists. */
    private static JsonElement toJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }

        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }

        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }

        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }

        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((key, entry) -> object.add((String) key, toJson(entry)));
            return object;
        }

        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            list.forEach(element -> array.add(toJson(element)));
            return array;
        }

        throw new AssertionError("Content was not normalized: " + value.getClass().getName());
    }

    // ---- successful responses ------------------------------------------------------------------------------------

    static SystemOneResponse readResponse(QuestionSet request, int status, String body, String requestId) {
        Reader reader = new Reader(status, requestId);
        JsonObject root = reader.parseObject(body);

        SystemOneResponse.Builder response = SystemOneResponse.builder(request)
                .model(reader.nonBlankString(root, "model", "model"))
                .usage(reader.usage(root))
                .requestId(requestId);

        JsonObject answers = reader.object(root, "answers", "answers");

        for (QuestionKey<?> key : request.keys().values()) {
            String path = "answers." + key.name();
            JsonObject json = reader.object(answers, key.name(), path);
            Answer answer = reader.answer(key.question(), json, path);

            try {
                response.put(key, answer);
            } catch (IllegalArgumentException e) {
                throw reader.invalid(path, e.getMessage(), e);
            }
        }

        return response.build();
    }

    static List<ModelInfo> readModels(int status, String body, String requestId) {
        Reader reader = new Reader(status, requestId);
        JsonObject root = reader.parseObject(body);
        JsonElement models = root.get("models");

        if (models == null || !models.isJsonArray()) {
            throw reader.invalid("models", "expected an array", null);
        }

        List<ModelInfo> result = new ArrayList<>();
        int index = 0;

        for (JsonElement element : models.getAsJsonArray()) {
            String path = "models[" + index++ + "]";

            if (!element.isJsonObject()) {
                throw reader.invalid(path, "expected an object", null);
            }

            JsonObject model = element.getAsJsonObject();
            result.add(new ModelInfo(
                    reader.nonBlankString(model, "name", path + ".name"),
                    reader.string(model, "description", path + ".description"),
                    reader.string(model, "release_date", path + ".release_date")));
        }

        return List.copyOf(result);
    }

    // ---- error responses -----------------------------------------------------------------------------------------

    /**
     * Turns an error response into an exception. Never fails: an unreadable body falls back to the raw text.
     *
     * @param apiKey masked out of the message if the body echoes it
     */
    static JevException readError(int status, String body, String requestId, Duration retryAfter, String apiKey) {
        String message = null;
        String errorType = null;

        try {
            JsonElement root = JsonParser.parseString(body == null ? "" : body);

            if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                JsonElement detail = object.get("detail");
                JsonElement error = object.get("error");

                message = firstNonBlank(describe(detail), describe(error), text(object.get("message")));
                errorType = firstNonBlank(
                        detail != null && detail.isJsonObject() ? text(detail.getAsJsonObject().get("error_type")) : null,
                        error != null && error.isJsonObject() ? text(error.getAsJsonObject().get("type")) : null,
                        text(object.get("error_type")));
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            // Not JSON, or not the shape we know: use the raw body below.
        }

        if (message == null) {
            message = body == null || body.isBlank() ? "(empty body)" : body.strip();
        }

        return JevException.httpError(status, truncate(mask(message, apiKey)), requestId, errorType, retryAfter);
    }

    /** {@code detail} or {@code error} as text: a string, an object's {@code message}, or FastAPI's list of errors. */
    private static String describe(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        if (element.isJsonPrimitive()) {
            return text(element);
        }

        if (element.isJsonObject()) {
            return text(element.getAsJsonObject().get("message"));
        }

        List<String> errors = new ArrayList<>();

        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }

            String msg = text(entry.getAsJsonObject().get("msg"));

            if (msg == null) {
                continue;
            }

            List<String> location = new ArrayList<>();
            JsonElement loc = entry.getAsJsonObject().get("loc");

            if (loc != null && loc.isJsonArray()) {
                for (JsonElement segment : loc.getAsJsonArray()) {
                    String part = segment.isJsonPrimitive() ? segment.getAsString() : segment.toString();

                    if (!"body".equals(part)) {
                        location.add(part);
                    }
                }
            }

            errors.add(location.isEmpty() ? msg : String.join(".", location) + ": " + msg);
        }

        return errors.isEmpty() ? null : String.join("; ", errors);
    }

    private static String text(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }

        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }

        return null;
    }

    static String mask(String text, String apiKey) {
        if (apiKey == null || apiKey.length() < MIN_MASKED_KEY_LENGTH) {
            return text;
        }

        return text.replace(apiKey, REDACTED);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_MESSAGE ? text : text.substring(0, MAX_ERROR_MESSAGE) + "…";
    }

    // ---- validating reader ---------------------------------------------------------------------------------------

    /** Reads required fields and reports the first problem as a {@link JevException} naming the field path. */
    private static final class Reader {

        private final int status;
        private final String requestId;

        Reader(int status, String requestId) {
            this.status = status;
            this.requestId = requestId;
        }

        JevException invalid(String path, String message, Throwable cause) {
            return JevException.invalidResponse(status, path, message, requestId, cause);
        }

        JsonObject parseObject(String body) {
            JsonElement root;

            try {
                root = JsonParser.parseString(body == null ? "" : body);
            } catch (JsonParseException e) {
                throw JevException.invalidResponse(status, null, "body is not valid JSON", requestId, e);
            }

            if (!root.isJsonObject()) {
                throw JevException.invalidResponse(status, null, "body is not a JSON object", requestId, null);
            }

            return root.getAsJsonObject();
        }

        JsonObject object(JsonObject parent, String field, String path) {
            JsonElement value = parent.get(field);

            if (value == null || value.isJsonNull()) {
                throw invalid(path, "missing", null);
            }

            if (!value.isJsonObject()) {
                throw invalid(path, "expected an object", null);
            }

            return value.getAsJsonObject();
        }

        String string(JsonObject parent, String field, String path) {
            JsonElement value = parent.get(field);

            if (value == null || value.isJsonNull()) {
                throw invalid(path, "missing", null);
            }

            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw invalid(path, "expected a string", null);
            }

            return value.getAsString();
        }

        String nonBlankString(JsonObject parent, String field, String path) {
            String value = string(parent, field, path);

            if (value.isBlank()) {
                throw invalid(path, "must not be blank", null);
            }

            return value;
        }

        double number(JsonObject parent, String field, String path) {
            return number(parent.get(field), path);
        }

        double number(JsonElement value, String path) {
            if (value == null || value.isJsonNull()) {
                throw invalid(path, "missing", null);
            }

            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw invalid(path, "expected a number", null);
            }

            double number = value.getAsDouble();

            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw invalid(path, "expected a finite number", null);
            }

            return number;
        }

        long count(JsonObject parent, String field, String path) {
            number(parent, field, path);
            BigDecimal value = parent.get(field).getAsBigDecimal();

            try {
                BigInteger integer = value.toBigIntegerExact();

                if (integer.signum() < 0) {
                    throw invalid(path, "must not be negative", null);
                }

                return integer.longValueExact();
            } catch (ArithmeticException e) {
                throw invalid(path, "expected a whole number", e);
            }
        }

        Usage usage(JsonObject root) {
            JsonObject usage = object(root, "usage", "usage");
            return new Usage(count(usage, "input_tokens", "usage.input_tokens"),
                    count(usage, "output_tokens", "usage.output_tokens"));
        }

        Answer answer(Question<?> question, JsonObject json, String path) {
            String expectedType = typeName(question);
            String type = string(json, "type", path + ".type");

            if (!type.equals(expectedType)) {
                throw invalid(path + ".type", "expected \"" + expectedType + "\" for this question, got \"" + type + "\"",
                        null);
            }

            try {
                if (question instanceof NoulQuestion) {
                    return new NoulAnswer(number(json, "noul", path + ".noul"));
                }

                if (question instanceof ChoiceQuestion) {
                    return new ChoiceAnswer(
                            string(json, "choice", path + ".choice"),
                            number(json, "confidence", path + ".confidence"),
                            probabilitiesByName(object(json, "probabilities", path + ".probabilities"),
                                    path + ".probabilities"));
                }

                if (question instanceof ScoreQuestion score) {
                    int levels = score.levels().size();
                    return new ScoreAnswer(
                            number(json, "score", path + ".score"),
                            number(json, "confidence", path + ".confidence"),
                            legend(object(json, "legend", path + ".legend"), levels, path + ".legend"),
                            probabilitiesByLevel(object(json, "probabilities", path + ".probabilities"), levels,
                                    path + ".probabilities"));
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                // The answer records enforce ranges and consistency; report which answer broke them.
                throw invalid(path, e.getMessage(), e);
            }

            throw new AssertionError("Unhandled question type " + question.getClass().getName());
        }

        private Map<String, Double> probabilitiesByName(JsonObject json, String path) {
            Map<String, Double> probabilities = new LinkedHashMap<>();

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                probabilities.put(entry.getKey(), number(entry.getValue(), path + "." + entry.getKey()));
            }

            return probabilities;
        }

        private List<Double> probabilitiesByLevel(JsonObject json, int levels, String path) {
            requireLevelKeys(json, levels, path);
            List<Double> probabilities = new ArrayList<>(levels);

            for (int level = 0; level < levels; level++) {
                probabilities.add(number(json.get(String.valueOf(level)), path + "." + level));
            }

            return probabilities;
        }

        private List<String> legend(JsonObject json, int levels, String path) {
            requireLevelKeys(json, levels, path);
            List<String> legend = new ArrayList<>(levels);

            for (int level = 0; level < levels; level++) {
                JsonElement description = json.get(String.valueOf(level));

                if (description == null || description.isJsonNull()) {
                    throw invalid(path + "." + level, "missing", null);
                }

                // Structured level descriptions are kept as their JSON text.
                boolean isString = description.isJsonPrimitive() && description.getAsJsonPrimitive().isString();
                legend.add(isString ? description.getAsString() : description.toString());
            }

            return legend;
        }

        private void requireLevelKeys(JsonObject json, int levels, String path) {
            if (json.size() != levels) {
                throw invalid(path, "expected " + levels + " levels, got " + json.size(), null);
            }

            for (int level = 0; level < levels; level++) {
                if (!json.has(String.valueOf(level))) {
                    throw invalid(path + "." + level, "missing", null);
                }
            }
        }

        private static String typeName(Question<?> question) {
            if (question instanceof ChoiceQuestion) {
                return "choice";
            }

            if (question instanceof ScoreQuestion) {
                return "score";
            }

            if (question instanceof NoulQuestion) {
                return "noul";
            }

            throw new AssertionError("Unhandled question type " + question.getClass().getName());
        }
    }
}
