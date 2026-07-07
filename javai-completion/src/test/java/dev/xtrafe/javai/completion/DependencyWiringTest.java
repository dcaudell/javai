package dev.xtrafe.javai.completion;

import dev.xtrafe.javai.collections.JavAIGraphNode;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves javai-completion's dependencies (javai-collections, javai-runtime, Spring AI)
 * resolve and compile. No real PromptContext/CompletionRequest logic exists yet.
 *
 * The ChatModel reference is load-bearing, not decorative: this module originally
 * depended on the nonexistent GA artifact "spring-ai-core" (it only ever existed as
 * milestones M5/M6 before Spring AI split it into spring-ai-model + spring-ai-client-chat),
 * and this test's earlier version never referenced any Spring AI class -- so the missing
 * dependency wasn't caught until a real `mvn install`. Keep an actual Spring AI type in
 * scope here so a future artifact-layout change fails the build, not just discovery.
 */
class DependencyWiringTest {

    @Test
    void dependenciesAreOnTheClasspath() {
        assertNotNull(new EmbeddingVector(new float[] {1f}, "placeholder", 1, Instant.now()));
        assertNotNull(JavAIGraphNode.class);
        assertNotNull(ChatModel.class);
    }
}
