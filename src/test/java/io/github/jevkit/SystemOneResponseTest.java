package io.github.jevkit;

import io.github.jevkit.model.ChoiceAnswer;
import io.github.jevkit.model.ChoiceQuestion;
import io.github.jevkit.model.NoulAnswer;
import io.github.jevkit.model.NoulQuestion;
import io.github.jevkit.model.ScoreAnswer;
import io.github.jevkit.model.ScoreQuestion;
import io.github.jevkit.model.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemOneResponseTest {

    private static final Usage USAGE = new Usage(342, 42);

    private QuestionKey<ChoiceAnswer> priority;
    private QuestionKey<ScoreAnswer> severity;
    private QuestionKey<NoulAnswer> urgent;
    private QuestionSet questions;

    @BeforeEach
    void setUp() {
        QuestionSet.Builder request = QuestionSet.builder("El servidor se cayó y nadie puede entrar");
        priority = request.add("priority", ChoiceQuestion.of("¿Qué prioridad?", "baja", "media", "critica"));
        severity = request.add("severity", ScoreQuestion.of("¿Qué tan grave?", "Leve", "Moderado", "Grave"));
        urgent = request.add("urgent", new NoulQuestion("¿Es urgente?"));
        questions = request.build();
    }

    @Test
    void returnsTypedAnswersWithoutCasts() {
        SystemOneResponse response = SystemOneResponse.builder(questions)
                .model("jev-1.13.0")
                .usage(USAGE)
                .requestId("req_00000000000000000000000000000001")
                .answer(priority, new ChoiceAnswer("critica", 1.0, Map.of("critica", 1.0, "media", 0.0, "baja", 0.0)))
                .answer(severity, new ScoreAnswer(1.9, 0.8, List.of("Leve", "Moderado", "Grave"), List.of(0.0, 0.1, 0.9)))
                .answer(urgent, new NoulAnswer(0.98))
                .build();

        ChoiceAnswer priorityAnswer = response.get(priority);
        ScoreAnswer severityAnswer = response.get(severity);
        NoulAnswer urgentAnswer = response.get(urgent);

        assertEquals("critica", priorityAnswer.choice());
        assertEquals(2, severityAnswer.mostLikelyLevel());
        assertTrue(urgentAnswer.isTrue(0.9));
        assertEquals("jev-1.13.0", response.model());
        assertEquals(USAGE, response.usage());
        assertEquals(Optional.of("req_00000000000000000000000000000001"), response.requestId());
        assertEquals(List.of("priority", "severity", "urgent"), List.copyOf(response.answers().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> response.answers().clear());
    }

    @Test
    void rejectsKeysFromAnotherRequest() {
        QuestionSet.Builder other = QuestionSet.builder("other");
        QuestionKey<NoulAnswer> foreign = other.add("urgent", new NoulQuestion("¿Es urgente?"));
        SystemOneResponse response = completeResponse();

        assertThrows(IllegalArgumentException.class, () -> response.get(foreign));
        assertThrows(IllegalArgumentException.class,
                () -> SystemOneResponse.builder(questions).answer(foreign, new NoulAnswer(0.5)));
    }

    @Test
    void everyQuestionNeedsExactlyOneAnswer() {
        SystemOneResponse.Builder missing = SystemOneResponse.builder(questions).model("jev-1.13.0").usage(USAGE)
                .answer(urgent, new NoulAnswer(0.5));

        IllegalStateException exception = assertThrows(IllegalStateException.class, missing::build);
        assertEquals("No answer for [priority, severity]", exception.getMessage());
        assertThrows(IllegalArgumentException.class, () -> missing.answer(urgent, new NoulAnswer(0.6)));
    }

    @Test
    void answersMustMatchTheQuestionsOptionsAndLevels() {
        SystemOneResponse.Builder builder = SystemOneResponse.builder(questions);

        assertThrows(IllegalArgumentException.class,
                () -> builder.answer(priority, new ChoiceAnswer("alta", 1.0, Map.of("alta", 1.0, "baja", 0.0))));
        assertThrows(IllegalArgumentException.class,
                () -> builder.answer(severity, new ScoreAnswer(0.5, 0.5, List.of("Leve", "Grave"), List.of(0.5, 0.5))));
    }

    @Test
    void theParserPathStillChecksTheAnswerType() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SystemOneResponse.builder(questions).put(urgent, new ChoiceAnswer("a", 1.0, Map.of("a", 1.0))));

        assertEquals("Question 'urgent' expects a NoulAnswer, got a ChoiceAnswer", exception.getMessage());
    }

    @Test
    void modelAndUsageAreRequired() {
        assertThrows(IllegalStateException.class, () -> answered(SystemOneResponse.builder(questions).usage(USAGE)).build());
        assertThrows(IllegalStateException.class, () -> answered(SystemOneResponse.builder(questions).model("m")).build());
    }

    private SystemOneResponse completeResponse() {
        return answered(SystemOneResponse.builder(questions).model("jev-1.13.0").usage(USAGE)).build();
    }

    private SystemOneResponse.Builder answered(SystemOneResponse.Builder builder) {
        return builder
                .answer(priority, new ChoiceAnswer("baja", 0.9, Map.of("baja", 0.9, "media", 0.1, "critica", 0.0)))
                .answer(severity, new ScoreAnswer(0.2, 0.9, List.of("Leve", "Moderado", "Grave"), List.of(0.8, 0.2, 0.0)))
                .answer(urgent, new NoulAnswer(0.1));
    }
}
