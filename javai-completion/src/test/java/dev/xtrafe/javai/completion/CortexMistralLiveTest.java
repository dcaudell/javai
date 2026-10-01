package dev.xtrafe.javai.completion;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Against Mistral's real endpoint -- a paid call, so tagged {@code "requires-model"} (excluded by default,
 * see the root {@code pom.xml}) and skipped without {@code MISTRAL_API_KEY}. Run it explicitly:
 * {@code MISTRAL_API_KEY=... mvn -pl javai-completion -am test -Dtest=CortexMistralLiveTest -Djavai.excludedTestGroups= -Dsurefire.failIfNoSpecifiedTests=false}.
 */
@Tag("requires-model")
@EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".+")
class CortexMistralLiveTest {

    private static final String MODEL = "mistral-small-latest";

    private final Cortex cortex = CortexMistral.builder()
            .apiKey(System.getenv("MISTRAL_API_KEY"))
            .model(MODEL)
            .build();

    @Test
    void completeReturnsARealAnswerFromARealModel() {
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("Reply with exactly the single word: acknowledged")
                .maxTokens(20)
                .temperature(0.0)
                .build());

        assertFalse(result.text().isBlank(), "a real model must produce some real text");
        assertEquals("mistral", result.providerId());
        assertEquals(MODEL, result.modelId());
    }

    @Test
    void completeStreamingDeliversTheAnswerAsTokenChunks() {
        List<String> chunks = new ArrayList<>();
        cortex.completeStreaming(
                CompletionRequest.builder().prompt("Count from one to three.").maxTokens(40).build(),
                chunks::add);

        assertFalse(chunks.isEmpty(), "streaming must deliver at least one chunk");
        assertFalse(String.join("", chunks).isBlank());
    }

    record Planet(String name, int moons, String note) {
    }

    /** OMI-68: Mistral honours the strict json_schema envelope -- prose and a typed value side by side. */
    @Test
    void aTypedRequestComesBackAsItsTypeWithItsProse() {
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("How many moons does Mars have? Explain briefly, then answer. Put null in note.")
                .responseType(Planet.class)
                .withCompletion()
                .temperature(0.0)
                .build());

        Planet mars = result.as(Planet.class).orElseThrow();
        assertEquals(2, mars.moons(), result.text());
        assertFalse(result.completion().isBlank(), result.text());
    }

    @Test
    void aListAndAnOptionalComeBackAsAskedFor() {
        Set<Planet> giants = cortex.complete(CompletionRequest.builder()
                        .prompt("List the four giant planets of the Solar System with their moon counts.")
                        .responseSetOf(Planet.class)
                        .temperature(0.0)
                        .build())
                .asSet(Planet.class).orElseThrow();
        assertEquals(4, giants.size(), giants.toString());

        CompletionResult none = cortex.complete(CompletionRequest.builder()
                .prompt("Name a planet of the Solar System that is made of cheese. If there is none, answer null.")
                .responseOptional(Planet.class)
                .temperature(0.0)
                .build());
        assertTrue(none.as(Planet.class).isEmpty(), none.text());
    }
}
