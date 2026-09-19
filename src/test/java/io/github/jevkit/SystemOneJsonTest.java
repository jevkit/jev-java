package io.github.jevkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jevkit.model.ChoiceAnswer;
import io.github.jevkit.model.ChoiceQuestion;
import io.github.jevkit.model.ModelInfo;
import io.github.jevkit.model.NoulAnswer;
import io.github.jevkit.model.NoulQuestion;
import io.github.jevkit.model.ScoreAnswer;
import io.github.jevkit.model.ScoreQuestion;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemOneJsonTest {

    private static final String REQUEST_ID = "req_00000000000000000000000000000001";

    // ---- request -------------------------------------------------------------------------------------------------

    @Test
    void requestMatchesTheDocumentedWireFormat() {
        QuestionSet.Builder request = QuestionSet.builder("El servidor se cayó y nadie puede entrar");
        request.add("prioridad", ChoiceQuestion.builder("¿Qué prioridad tiene?")
                .option("baja", "Sin riesgo inmediato")
                .option("media")
                .option("critica", "Afecta a todos")
                .build());
        request.add("gravedad", ScoreQuestion.of("¿Qué tan grave es?", "Leve", "Grave"));
        request.add("es_urgente", new NoulQuestion("¿Requiere atención inmediata?"));

        String json = SystemOneJson.requestBody(request.build(), "jev-latest");

        assertEquals("{\"model\":\"jev-latest\",\"state\":\"El servidor se cayó y nadie puede entrar\",\"questions\":{"
                + "\"prioridad\":{\"type\":\"choice\",\"instructions\":\"¿Qué prioridad tiene?\","
                + "\"criteria\":{\"baja\":\"Sin riesgo inmediato\",\"media\":null,\"critica\":\"Afecta a todos\"}},"
                + "\"gravedad\":{\"type\":\"score\",\"instructions\":\"¿Qué tan grave es?\",\"criteria\":[\"Leve\",\"Grave\"]},"
                + "\"es_urgente\":{\"type\":\"noul\",\"instructions\":\"¿Requiere atención inmediata?\"}}}", json);
    }

    @Test
    void requestUsesThePerRequestModelAndSendsStructuredContentInOrder() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("subject", "Refund <urgent>");
        state.put("amount", 12.5);
        state.put("items", List.of(1, 2));

        QuestionSet.Builder request = QuestionSet.builder(state).model("jev-1.13.0");
        request.add("urgent", new NoulQuestion("Is it urgent?", "Mentions a deadline", null));

        JsonObject json = JsonParser.parseString(SystemOneJson.requestBody(request.build(), "jev-latest")).getAsJsonObject();

        assertEquals("jev-1.13.0", json.get("model").getAsString());
        assertEquals("{\"subject\":\"Refund <urgent>\",\"amount\":12.5,\"items\":[1,2]}", json.get("state").toString());
        assertEquals("{\"true\":\"Mentions a deadline\"}", json.getAsJsonObject("questions")
                .getAsJsonObject("urgent").get("criteria").toString());
    }

    // ---- successful responses, using bodies returned by jev-1.13.0 on 2026-09-18 -----------------------------------

    @Test
    void readsRealResponses() {
        QuestionSet.Builder request = QuestionSet.builder("El servidor se cayó y nadie puede entrar");
        QuestionKey<ChoiceAnswer> prioridad = request.add("prioridad",
                ChoiceQuestion.of("¿Qué prioridad tiene?", "baja", "media", "critica"));
        QuestionKey<ScoreAnswer> gravedad = request.add("gravedad",
                ScoreQuestion.of("¿Qué tan grave es?", "Nada grave", "Leve", "Moderado", "Grave", "Catastrófico"));
        QuestionKey<NoulAnswer> positivo = request.add("es_positivo", new NoulQuestion("¿Este texto es positivo?"));

        SystemOneResponse response = SystemOneJson.readResponse(request.build(), 200, """
                {"model":"jev-1.13.0","answers":{
                  "prioridad":{"type":"choice","choice":"critica","confidence":1.0,"probabilities":{"critica":1.0,"media":0.0,"baja":0.0}},
                  "gravedad":{"type":"score","score":2.96,"confidence":0.87,
                    "legend":{"0":"Nada grave","1":"Leve","2":"Moderado","3":"Grave","4":"Catastrófico"},
                    "probabilities":{"0":0.0,"1":0.0,"2":0.09,"3":0.85,"4":0.06}},
                  "es_positivo":{"type":"noul","noul":0.98}},
                 "usage":{"input_tokens":342,"output_tokens":42}}
                """, REQUEST_ID);

        assertEquals("critica", response.get(prioridad).choice());
        assertEquals(3, response.get(gravedad).mostLikelyLevel());
        assertEquals("Grave", response.get(gravedad).legend().get(3));
        assertEquals(0.98, response.get(positivo).value());
        assertEquals("jev-1.13.0", response.model());
        assertEquals(342, response.usage().inputTokens());
        assertEquals(Optional.of(REQUEST_ID), response.requestId());
    }

    @Test
    void ignoresUnknownFieldsAndExtraAnswers() {
        QuestionSet.Builder request = QuestionSet.builder("hola");
        QuestionKey<NoulAnswer> a = request.add("a", new NoulQuestion("¿Es un saludo?"));

        SystemOneResponse response = SystemOneJson.readResponse(request.build(), 200, """
                {"model":"jev-1.13.0","future_field":{},"answers":{
                  "a":{"type":"noul","noul":0.98,"future":true},
                  "not_asked":{"type":"noul","noul":0.1}},
                 "usage":{"input_tokens":272,"output_tokens":20,"cached_tokens":0}}
                """, null);

        assertEquals(0.98, response.get(a).value());
        assertEquals(List.of("a"), List.copyOf(response.answers().keySet()));
    }

    // ---- malformed successful responses: never read as valid values ---------------------------------------------

    @Test
    void missingAnswerValueIsRejectedInsteadOfReadAsZero() {
        JevException exception = readNoul("{\"model\":\"m\",\"answers\":{\"toxic\":{\"type\":\"noul\"}},"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");

        assertEquals(Optional.of("answers.toxic.noul"), exception.fieldPath());
        assertEquals(OptionalInt.of(200), exception.statusCode());
        assertTrue(exception.getMessage().contains("answers.toxic.noul: missing"), exception.getMessage());
    }

    @Test
    void everyStructuralProblemNamesItsField() {
        assertEquals("answers.toxic", readNoul("{\"model\":\"m\",\"answers\":{},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
                .fieldPath().orElseThrow());
        assertEquals("answers.toxic.type", readNoul("{\"model\":\"m\",\"answers\":{\"toxic\":{\"type\":\"choice\"}},"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").fieldPath().orElseThrow());
        assertEquals("answers.toxic.noul", readNoul("{\"model\":\"m\",\"answers\":{\"toxic\":{\"type\":\"noul\",\"noul\":\"high\"}},"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").fieldPath().orElseThrow());
        assertEquals("answers.toxic", readNoul("{\"model\":\"m\",\"answers\":{\"toxic\":{\"type\":\"noul\",\"noul\":1.7}},"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").fieldPath().orElseThrow());
        assertEquals("usage", readNoul("{\"model\":\"m\",\"answers\":{\"toxic\":{\"type\":\"noul\",\"noul\":0.1}}}")
                .fieldPath().orElseThrow());
        assertEquals("usage.input_tokens", readNoul("{\"model\":\"m\",\"answers\":{\"toxic\":{\"type\":\"noul\",\"noul\":0.1}},"
                + "\"usage\":{\"input_tokens\":1.5,\"output_tokens\":1}}").fieldPath().orElseThrow());
        assertEquals("model", readNoul("{\"model\":\"\",\"answers\":{},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
                .fieldPath().orElseThrow());
    }

    @Test
    void invalidJsonIsReportedWithTheParserAsCause() {
        JevException exception = readNoul("<html>Bad gateway</html>");

        assertFalse(exception.fieldPath().isPresent());
        assertTrue(exception.getMessage().contains("not valid JSON") || exception.getMessage().contains("not a JSON object"),
                exception.getMessage());
    }

    @Test
    void choiceAnswerMustCoverExactlyTheQuestionsOptions() {
        QuestionSet.Builder request = QuestionSet.builder("x");
        request.add("team", ChoiceQuestion.of("Which?", "billing", "technical"));

        JevException exception = assertThrows(JevException.class, () -> SystemOneJson.readResponse(request.build(), 200,
                "{\"model\":\"m\",\"answers\":{\"team\":{\"type\":\"choice\",\"choice\":\"billing\",\"confidence\":0.9,"
                        + "\"probabilities\":{\"billing\":0.9,\"sales\":0.1}}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                null));

        assertEquals(Optional.of("answers.team"), exception.fieldPath());
    }

    @Test
    void scoreAnswerNeedsOneEntryPerLevel() {
        QuestionSet.Builder request = QuestionSet.builder("x");
        request.add("severity", ScoreQuestion.of("How bad?", "low", "mid", "high"));

        JevException exception = assertThrows(JevException.class, () -> SystemOneJson.readResponse(request.build(), 200,
                "{\"model\":\"m\",\"answers\":{\"severity\":{\"type\":\"score\",\"score\":1.0,\"confidence\":0.5,"
                        + "\"legend\":{\"0\":\"low\",\"1\":\"mid\",\"2\":\"high\"},\"probabilities\":{\"0\":0.5,\"1\":0.5}}},"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                null));

        assertEquals(Optional.of("answers.severity.probabilities"), exception.fieldPath());
    }

    // ---- models --------------------------------------------------------------------------------------------------

    @Test
    void readsTheModelList() {
        List<ModelInfo> models = SystemOneJson.readModels(200,
                "{\"models\":[{\"name\":\"jev-latest\",\"description\":\"Flagship\",\"release_date\":\"2026-09-15\"}]}", null);

        assertEquals(List.of(new ModelInfo("jev-latest", "Flagship", "2026-09-15")), models);
        assertEquals(Optional.of("models[0].name"), assertThrows(JevException.class,
                () -> SystemOneJson.readModels(200, "{\"models\":[{\"description\":\"x\"}]}", null)).fieldPath());
    }

    // ---- error responses, using bodies returned by the API on 2026-09-18 ------------------------------------------

    @Test
    void readsAuthenticationErrors() {
        JevException exception = SystemOneJson.readError(401,
                "{\"detail\":{\"error_type\":\"authentication_error\",\"message\":\"Cannot authenticate with the server. "
                        + "Please check your API key and try again.\"}}", REQUEST_ID, null, "sk-secret-key");

        assertEquals(OptionalInt.of(401), exception.statusCode());
        assertEquals(Optional.of("authentication_error"), exception.errorType());
        assertEquals("HTTP 401: Cannot authenticate with the server. Please check your API key and try again. (request "
                + REQUEST_ID + ")", exception.getMessage());
    }

    @Test
    void readsValidationErrorsAsFieldList() {
        JevException exception = SystemOneJson.readError(422,
                "{\"detail\":[{\"type\":\"missing\",\"loc\":[\"body\"],\"msg\":\"Field required\",\"input\":null},"
                        + "{\"type\":\"missing\",\"loc\":[\"body\",\"questions\",\"q\",\"criteria\"],\"msg\":\"Field required\"}]}",
                null, null, "sk-secret-key");

        assertEquals("HTTP 422: Field required; questions.q.criteria: Field required", exception.getMessage());
        assertFalse(exception.errorType().isPresent());
    }

    @Test
    void neverFailsOnUnexpectedErrorBodiesAndKeepsRetryAfter() {
        JevException html = SystemOneJson.readError(529, "<html>Overloaded</html>", null, Duration.ofSeconds(7), "sk-secret-key");
        assertEquals("HTTP 529: <html>Overloaded</html>", html.getMessage());
        assertEquals(Optional.of(Duration.ofSeconds(7)), html.retryAfter());

        assertEquals("HTTP 500: (empty body)", SystemOneJson.readError(500, "", null, null, "sk-secret-key").getMessage());
        assertEquals("HTTP 500: (empty body)", SystemOneJson.readError(500, null, null, null, "sk-secret-key").getMessage());
        assertEquals("HTTP 400: boom", SystemOneJson.readError(400, "{\"error\":{\"type\":\"x\",\"message\":\"boom\"}}",
                null, null, "sk-secret-key").getMessage());

        String longBody = "x".repeat(2000);
        assertTrue(SystemOneJson.readError(500, longBody, null, null, "sk-secret-key").getMessage().length() < 600);
    }

    @Test
    void masksTheApiKeyIfTheErrorBodyEchoesIt() {
        JevException exception = SystemOneJson.readError(401, "{\"detail\":\"Invalid key sk-secret-key\"}", null, null,
                "sk-secret-key");

        assertEquals("HTTP 401: Invalid key [REDACTED]", exception.getMessage());
    }

    private static JevException readNoul(String body) {
        QuestionSet.Builder request = QuestionSet.builder("x");
        request.add("toxic", new NoulQuestion("Is it toxic?"));
        return assertThrows(JevException.class, () -> SystemOneJson.readResponse(request.build(), 200, body, null));
    }
}
