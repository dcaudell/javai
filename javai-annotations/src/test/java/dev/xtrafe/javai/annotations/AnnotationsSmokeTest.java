package dev.xtrafe.javai.annotations;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not a design test -- just proves the annotation vocabulary is present, retained at
 * runtime, and applicable to the elements the spec assigns it to (doc/spec/vector-core.md,
 * vector-collections.md, codegen-guidance.md).
 */
class AnnotationsSmokeTest {

    @JavAIVectorizable
    @JavAIGraphNode
    static class Sample {

        @Vectorize
        @SearchVisibility(SearchVisibility.Visibility.PUBLIC)
        String title;

        @VectorizeIgnore
        String internalId;

        @Summary
        String note;

        @Requires("title != null")
        @Ensures("result != null")
        @Intent("Demonstrates the full annotation vocabulary in one place")
        @AgentWritable
        String normalize() {
            return title;
        }

        @Frozen
        @Nondeterministic
        @Costly
        String regenerateEmbedding(@EmbeddingModel("e5-large-v3") String model) {
            return title;
        }

        @HumanOnly
        void doNotTouch() {
        }
    }

    @Test
    void classLevelAnnotationsArePresent() {
        assertTrue(Sample.class.isAnnotationPresent(JavAIVectorizable.class));
        assertTrue(Sample.class.isAnnotationPresent(JavAIGraphNode.class));
    }

    @Test
    void fieldLevelAnnotationsArePresent() throws NoSuchFieldException {
        Field title = Sample.class.getDeclaredField("title");
        assertTrue(title.isAnnotationPresent(Vectorize.class));
        assertTrue(title.isAnnotationPresent(SearchVisibility.class));

        Field internalId = Sample.class.getDeclaredField("internalId");
        assertTrue(internalId.isAnnotationPresent(VectorizeIgnore.class));

        Field note = Sample.class.getDeclaredField("note");
        assertTrue(note.isAnnotationPresent(Summary.class));
    }

    @Test
    void methodLevelCodegenGuidanceAnnotationsArePresent() throws NoSuchMethodException {
        Method normalize = Sample.class.getDeclaredMethod("normalize");
        assertTrue(normalize.isAnnotationPresent(Requires.class));
        assertTrue(normalize.isAnnotationPresent(Ensures.class));
        assertTrue(normalize.isAnnotationPresent(Intent.class));
        assertTrue(normalize.isAnnotationPresent(AgentWritable.class));

        Method regenerate = Sample.class.getDeclaredMethod("regenerateEmbedding", String.class);
        assertTrue(regenerate.isAnnotationPresent(Frozen.class));
        assertTrue(regenerate.isAnnotationPresent(Nondeterministic.class));
        assertTrue(regenerate.isAnnotationPresent(Costly.class));

        Method doNotTouch = Sample.class.getDeclaredMethod("doNotTouch");
        assertTrue(doNotTouch.isAnnotationPresent(HumanOnly.class));
    }
}
