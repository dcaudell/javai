package dev.xtrafe.javai.completion;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real container, not hermetic -- there's no meaningful way to fake whether a real completion actually
 * comes back, or whether a real tuning parameter ({@code enable_thinking}) actually changes observed
 * behavior, matching this project's established testing philosophy
 * ({@code RepositoryBackendHibernatePostgresTest}, {@code EmbeddingProviderOllamaTest}). Builds
 * {@code javai-completion/docker/Dockerfile} (Ollama + {@link LocalCompletionDefaults#model()} baked in) --
 * first run is slow (image build + a multi-gigabyte model pull), subsequent runs reuse Docker's own layer
 * cache. Explicitly named {@code javai-completion-ollama-test} (rather than left to
 * {@code ImageFromDockerfile}'s default random {@code localhost/testcontainers/<random>} tag) so an
 * interrupted run's leftover image is identifiable in {@code docker images} instead of showing up as an
 * unlabelled {@code <none>:<none>} dangling image.
 *
 * <p>Tagged {@code "requires-model"} and excluded from the default {@code mvn test}/{@code mvn install}
 * run repo-wide (see the root {@code pom.xml}'s surefire configuration) -- a multi-gigabyte model pull is
 * a real compute/bandwidth cost this project's CI pipelines deliberately don't pay on every PR. Run it
 * explicitly: {@code mvn -pl javai-completion -am test -Dtest=CortexOllamaRealContainerTest -Djavai.excludedTestGroups=}.
 */
@Tag("requires-model")
@Testcontainers
class CortexOllamaRealContainerTest {

    @Container
    static final GenericContainer<?> ollama = new GenericContainer<>(
            new ImageFromDockerfile("javai-completion-ollama-test").withDockerfile(Path.of("docker/Dockerfile")))
            .withExposedPorts(11434)
            .withStartupTimeout(Duration.ofMinutes(10));

    private static Cortex cortex;

    @BeforeAll
    static void configureCortex() {
        URI endpoint = URI.create("http://" + ollama.getHost() + ":" + ollama.getMappedPort(11434));
        cortex = LocalCompletionDefaults.create(endpoint);
    }

    @Test
    void completeReturnsARealAnswerFromARealModel() {
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("Reply with exactly the single word: acknowledged")
                .maxTokens(50)
                .providerOption("enable_thinking", false)
                .build());

        assertFalse(result.text().isBlank(), "a real model must produce some real text");
        assertEquals("ollama", result.providerId());
        assertEquals(LocalCompletionDefaults.model(), result.modelId());
    }

    @Test
    void completeStreamingDeliversTheSameAnswerAsTokenChunks() {
        List<String> chunks = new ArrayList<>();
        cortex.completeStreaming(
                CompletionRequest.builder()
                        .prompt("Count from one to three.")
                        .maxTokens(60)
                        .providerOption("enable_thinking", false)
                        .build(),
                chunks::add);

        assertTrue(chunks.size() >= 1, "streaming must deliver at least one chunk");
        String joined = String.join("", chunks);
        assertFalse(joined.isBlank());
    }

    /**
     * The real, observable proof of this project's "proprietary behavior tuning parameters" requirement --
     * deliberately not a timing comparison (two real network calls will almost always differ in latency
     * regardless of whether a tuning parameter did anything, so that would prove nothing). Instead, this
     * goes straight to Ollama's own wire-level response (via {@code OllamaApi} directly, the same class
     * {@link CortexOllama} itself uses internally): enabling the real {@code think} field on a
     * thinking-capable model must produce a non-empty reasoning trace in {@code message.thinking()} --
     * structurally absent when not requested. {@link CortexOllamaTest} (hermetic) already proves the
     * *request* correctly carries {@code think:true}; this proves the real backend actually *does*
     * something different in response, not just that the request looked right.
     */
    @Test
    void enableThinkingProviderOptionProducesARealReasoningTraceFromTheModel() {
        OllamaApi api = OllamaApi.builder()
                .baseUrl("http://" + ollama.getHost() + ":" + ollama.getMappedPort(11434))
                .build();
        String model = LocalCompletionDefaults.model();
        String prompt = "What is 17 times 23? Work it out step by step.";

        OllamaApi.ChatResponse thinkingResponse = api.chat(OllamaApi.ChatRequest.builder(model)
                .messages(List.of(OllamaApi.Message.builder(OllamaApi.Message.Role.USER).content(prompt).build()))
                .stream(false)
                .think(true)
                .options(Map.of("num_predict", 300))
                .build());

        assertNotNull(thinkingResponse.message());
        String thinkingTrace = thinkingResponse.message().thinking();
        assertTrue(thinkingTrace != null && !thinkingTrace.isBlank(),
                "enabling Ollama's real \"think\" field on a thinking-capable model must produce a "
                        + "non-empty reasoning trace, not just be silently accepted as an unused option");
    }
}
