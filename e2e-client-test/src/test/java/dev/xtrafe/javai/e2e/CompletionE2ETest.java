package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.completion.CompletionRequest;
import dev.xtrafe.javai.completion.CompletionResult;
import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.completion.LocalCompletionDefaults;
import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.model.ContextableObject;
import dev.xtrafe.javai.model.PromptContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real end-to-end proof that the {@code javai-completion} connector layer and {@code javai-model}'s
 * {@code Contextable}/{@code PromptContext} RAG-integration primitives work together against a real
 * backend -- not a hermetic one. {@code javai-completion} was previously never wired into this project
 * ("this pass stayed scoped to javai-completion itself"); this is that wiring's first real usage.
 *
 * <p>The completion model is already baked into {@code JavAIEnvironment}/{@code MonolithicContainer}'s
 * shared image ({@code qwen3:8b}, alongside the embedding model) -- see {@code docker/Dockerfile} -- so no
 * separate container or model pull is needed here, only {@link JavAIEnvironment#cortex()} (already built
 * against the same Ollama instance the embedding provider is configured against).
 *
 * <p>Kept to two real completions/marshalling checks, not an exhaustive suite -- each real call against
 * {@code qwen3:8b} costs real wall-clock time (see {@code javai-completion}'s own
 * {@code CortexOllamaRealContainerTest} for the established pattern this mirrors: {@code enable_thinking}
 * explicitly disabled and token budgets bounded, since Qwen3 defaults to extended reasoning that can
 * otherwise exhaust a small budget before producing any real content).
 */
class CompletionE2ETest {

    @BeforeAll
    static void configureRealProviders() {
        JavAIEnvironment.ensureRunning();
    }

    @Test
    void realCompletionGroundedInArticleAndCommentsReturnsNonBlankText() {
        Article article = new Article("Local LLMs are getting genuinely useful",
                "Recent open-weight models run acceptably well on consumer hardware, closing much of the "
                        + "gap with hosted frontier models for everyday summarization tasks.");
        Comment first = new Comment("reader-one", "I've had good results running these models locally.");
        Comment second = new Comment("reader-two", "Still slower than a hosted API, but the privacy is worth it.");
        article.getComments().add(first);
        article.getComments().add(second);

        PromptContext context = PromptContext.builder()
                .sourceLabel("Article + reader comments")
                .entry(new ContextableObject<>(article))
                .entry(new ContextableObject<>(first))
                .entry(new ContextableObject<>(second))
                .build();

        Cortex cortex = JavAIEnvironment.cortex();
        CompletionResult result = cortex.complete(CompletionRequest.builder()
                .prompt("In one sentence, what are readers saying about this article?")
                .context(context)
                .maxTokens(80)
                .providerOption("enable_thinking", false)
                .build());

        assertFalse(result.text().isBlank(), "a real model grounded in real context must produce real text");
        assertEquals("ollama", result.providerId());
        assertEquals(LocalCompletionDefaults.model(), result.modelId());
    }

    @Test
    void contextableObjectMarshallingOfARealArticleExcludesInternalWovenStateButIncludesAnnotatedFields() {
        Article article = new Article("Marshalling proof", "Body text for the marshalling proof.");

        String rendered = new ContextableObject<>(article).toContext(PromptContext.builder().build());

        assertTrue(rendered.contains("Marshalling proof"), "the @PromptContext-annotated title must appear");
        assertTrue(rendered.contains("Body text for the marshalling proof."),
                "the @PromptContext-annotated body must appear");
        assertFalse(rendered.contains("\"id\""),
                "the entity id is not @PromptContext-annotated and must not leak through");
        assertFalse(rendered.contains("computedAt"),
                "EmbeddingVector's own field names must not leak through -- proves no cached embedding, "
                        + "dirty-tracking state, or other woven internals are marshalled by default");
        assertFalse(rendered.contains("dims"));
    }
}
