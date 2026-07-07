package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.collections.JavAIGraphNode;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves javai-persistence's dependencies (javai-collections, javai-runtime, Hibernate)
 * resolve and compile. No real JavAIPI/repository logic exists yet.
 */
class DependencyWiringTest {

    @Test
    void dependenciesAreOnTheClasspath() {
        assertNotNull(new EmbeddingVector(new float[] {1f}, "placeholder", 1, Instant.now()));
        assertNotNull(new Configuration());
        assertNotNull(JavAIGraphNode.class);
    }
}
