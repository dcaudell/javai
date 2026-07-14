package dev.xtrafe.javai.substrate;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.vector.EmbeddingVector;
import net.bytebuddy.ByteBuddy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Not a weaving test -- there's no weaving logic yet. This only proves the module's
 * declared dependencies (javai-annotations, javai-vector, javai-model, Byte Buddy) actually resolve
 * and are usable from this module, before any real implementation is written.
 */
class DependencyWiringTest {

    @JavAIVectorizable
    static class Placeholder {
    }

    @Test
    void dependenciesAreOnTheClasspath() {
        assertNotNull(new ByteBuddy());
        assertNotNull(new EmbeddingVector(new float[] {1f}, "placeholder", 1, Instant.now()));
        assertNotNull(Placeholder.class.getAnnotation(JavAIVectorizable.class));
    }
}
