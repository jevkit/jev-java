package io.github.jevkit;

import io.github.jevkit.model.ChoiceAnswer;
import io.github.jevkit.model.ChoiceQuestion;
import io.github.jevkit.model.ModelInfo;
import io.github.jevkit.model.NoulAnswer;
import io.github.jevkit.model.NoulQuestion;
import io.github.jevkit.model.ScoreAnswer;
import io.github.jevkit.model.ScoreQuestion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls the real API. Excluded from normal builds (each run makes three small, billed requests); run it with:
 *
 * <pre>
 * TYPESAFE_API_KEY=... ./mvnw test -Dgroups=live -DexcludedGroups= -Dtest=LiveApiTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * It prints what the API returned so the documented wire format can be checked against it.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = JevClient.API_KEY_ENV, matches = ".+")
class LiveApiTest {

    @Test
    void answersAllThreeQuestionTypes() {
        QuestionSet.Builder request = QuestionSet.builder("El servidor se cayó y nadie puede entrar");
        QuestionKey<ChoiceAnswer> priority = request.add("prioridad",
                ChoiceQuestion.of("¿Qué prioridad tiene?", "baja", "media", "critica"));
        QuestionKey<ScoreAnswer> severity = request.add("gravedad", ScoreQuestion.builder("¿Qué tan grave es?")
                .level("Nada grave")
                .level("Leve")
                .level(Map.of("what", "Grave", "examples", List.of("Nadie puede entrar al sistema")))
                .build());
        QuestionKey<NoulAnswer> urgent = request.add("urgente", new NoulQuestion("¿Requiere atención inmediata?"));

        try (JevClient client = JevClient.builder().build()) {
            SystemOneResponse response = client.evaluate(request.build());

            System.out.println("[live] model=" + response.model() + " requestId=" + response.requestId().orElse("-")
                    + " usage=" + response.usage());
            System.out.println("[live] prioridad=" + response.get(priority));
            System.out.println("[live] gravedad=" + response.get(severity));
            System.out.println("[live] urgente=" + response.get(urgent));

            assertTrue(response.model().startsWith("jev-"), response.model());
            assertTrue(response.requestId().isPresent(), "x-typesafe-request-id missing");
            assertEquals(3, response.get(severity).legend().size());
        }
    }

    @Test
    void listsModels() {
        try (JevClient client = JevClient.builder().build()) {
            List<ModelInfo> models = client.listModels();
            System.out.println("[live] models=" + models);

            assertTrue(models.stream().anyMatch(model -> model.name().equals(JevClient.DEFAULT_MODEL)), models.toString());
        }
    }

    @Test
    void rejectsAnInvalidKeyWithoutRetrying() {
        QuestionSet.Builder request = QuestionSet.builder("x");
        request.add("a", new NoulQuestion("x"));

        try (JevClient client = JevClient.builder().apiKey("sk-invalid-key-for-testing").build()) {
            JevException exception = assertThrows(JevException.class, () -> client.evaluate(request.build()));
            System.out.println("[live] invalid key -> " + exception.getMessage() + " errorType="
                    + exception.errorType().orElse("-"));

            assertEquals(OptionalInt.of(401), exception.statusCode());
        }
    }
}
