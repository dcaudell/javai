package dev.xtrafe.javai.completion;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
