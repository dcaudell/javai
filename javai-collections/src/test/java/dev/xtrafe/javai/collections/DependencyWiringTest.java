package dev.xtrafe.javai.collections;

import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIList;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves javai-collections' dependency on javai-runtime resolves and compiles --
 * including the module-placement fix (JavAIList lives in javai-runtime, not here).
 * No real KnowledgeGraph/VectorIndex logic exists yet.
 */
class DependencyWiringTest {

    static class Node implements JavAIGraphNode {
    }

    @Test
    void runtimeTypesAreVisible() {
        assertNotNull(new EmbeddingVector(new float[] {1f}, "placeholder", 1, Instant.now()));
        assertNotNull(new Node());
    }

    @Test
    void javaiListTypeIsResolvable() {
        // Compile-time check only: JavAIList<T> must resolve from javai-runtime here.
        Class<JavAIList> type = JavAIList.class;
        assertNotNull(type);
    }
}
