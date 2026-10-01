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
        @PromptContext
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

        @Query("select s from Sample s where s.title = :title")
        String declaredQuery(String title) {
            return title;
        }

        @Modifying(clearAutomatically = true)
        @Query(value = "update Sample s set s.note = 'x'", nativeQuery = true)
        int declaredWrite() {
            return 0;
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
        assertTrue(title.isAnnotationPresent(PromptContext.class));

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

    /** Persistence Bridge's declared-query pair (OMI-398), whose defaults are load-bearing: a @Query is JPQL
     *  and carries no countQuery unless it says so, and a @Modifying neither flushes nor clears by default. */
    @Test
    void declaredQueryAnnotationsArePresentWithTheirDefaults() throws NoSuchMethodException {
        Method read = Sample.class.getDeclaredMethod("declaredQuery", String.class);
        Query query = read.getAnnotation(Query.class);
        assertTrue(query != null && query.value().startsWith("select"));
        assertTrue(!query.nativeQuery() && query.countQuery().isEmpty());
        assertTrue(!read.isAnnotationPresent(Modifying.class));

        Method write = Sample.class.getDeclaredMethod("declaredWrite");
        assertTrue(write.getAnnotation(Query.class).nativeQuery());
        Modifying modifying = write.getAnnotation(Modifying.class);
        assertTrue(modifying.clearAutomatically() && !modifying.flushAutomatically());
    }
}
