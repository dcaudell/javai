package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptContextTest {

    @Test
    void ofWrapsPlainTextVerbatimWithNoMarshalling() {
        PromptContext context = PromptContext.of("hello world");

        assertEquals("hello world", context.toString());
    }

    @Test
    void unboundedByDefaultIncludesEveryEntryRegardlessOfSize() {
        PromptContext context = PromptContext.builder()
                .entry(new PlainTextEntry("a".repeat(500)))
                .entry(new PlainTextEntry("b".repeat(500)))
                .build();

        assertEquals(1000 + "\n\n".length(), context.toString().length());
    }

    @Test
    void stopsAtFirstEntryThatWouldOverflowAndDoesNotTryLaterSmallerOnes() {
        PromptContext context = PromptContext.builder()
                .maxLength(15)
                .entry(new PlainTextEntry("short"))       // fits
                .entry(new PlainTextEntry("this one is far too long to fit")) // doesn't fit -- stop here
                .entry(new PlainTextEntry("x"))            // would fit alone, but must not be tried
                .build();

        assertEquals("short", context.toString());
    }

    @Test
    void omissionIsSilentNoMarkerOfAnyKind() {
        PromptContext context = PromptContext.builder()
                .maxLength(3)
                .entry(new PlainTextEntry("way too long for the budget"))
                .build();

        assertEquals("", context.toString());
    }

    @Test
    void sourceLabelPrintsOnceAsAHeaderWhenSet() {
        PromptContext context = PromptContext.builder()
                .sourceLabel("SubgraphResult<Article,RelatesTo>[12 nodes]")
                .entry(new PlainTextEntry("finding one"))
                .entry(new PlainTextEntry("finding two"))
                .build();

        String rendered = context.toString();
        assertTrue(rendered.startsWith("[Source: SubgraphResult<Article,RelatesTo>[12 nodes]]\n"));
        assertEquals(1, rendered.split("\\[Source:").length - 1, "the header must appear exactly once");
    }

    @Test
    void noHeaderAtAllWhenSourceLabelIsUnset() {
        PromptContext context = PromptContext.builder().entry(new PlainTextEntry("plain")).build();

        assertFalse(context.toString().contains("[Source:"));
        assertEquals("plain", context.toString());
    }

    @Test
    void nestedPromptContextIgnoresTheOuterPromptsConfigAndUsesItsOwn() {
        PromptContext inner = PromptContext.builder()
                .maxLength(5)
                .entry(new PlainTextEntry("way too long to fit in five chars"))
                .build();
        PromptContext outer = PromptContext.builder().maxLength(10_000).build(); // large, irrelevant budget

        // inner enforces its OWN maxLength (5) even though the outer context passed in has a huge budget
        assertEquals("", inner.toContext(outer));
    }

    @Test
    void mergeConcatenatesEntriesAndKeepsThisContextsConfig() {
        PromptContext a = PromptContext.builder()
                .sourceLabel("a-label")
                .entry(new PlainTextEntry("first"))
                .build();
        PromptContext b = PromptContext.builder()
                .sourceLabel("b-label")
                .entry(new PlainTextEntry("second"))
                .build();

        PromptContext merged = a.merge(b);

        assertEquals("[Source: a-label]\nfirst\n\nsecond", merged.toString());
    }

    @Test
    void withMaxLengthReturnsACopyAndDoesNotMutateTheOriginal() {
        PromptContext original = PromptContext.builder()
                .entry(new PlainTextEntry("a".repeat(20)))
                .build();

        PromptContext bounded = original.withMaxLength(5);

        assertEquals("", bounded.toString());
        assertEquals("a".repeat(20), original.toString(), "the original must be unaffected");
    }

    @Test
    void negativeMaxLengthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PromptContext.builder().maxLength(-1).build());
    }

    /** Only the {@code @PromptContext}-annotated component ({@code name}) survives; {@code count} is
     *  excluded by default, since {@code @}{@link dev.xtrafe.javai.annotations.PromptContext} is an
     *  allowlist, not a blocklist. */
    record MixedAnnotatedSample(@dev.xtrafe.javai.annotations.PromptContext String name, int count) {
    }

    @Test
    void defaultMarshallOnlyIncludesPromptContextAnnotatedFields() {
        PromptContext context = PromptContext.builder().build();

        String json = context.defaultMarshall(new MixedAnnotatedSample("widgets", 3));

        assertEquals("{\"name\":\"widgets\"}", json);
    }

    @Test
    void contextableObjectDelegatesToDefaultMarshallAndRespectsTheAnnotationFilter() {
        PromptContext context = PromptContext.builder()
                .entry(new ContextableObject<>(new MixedAnnotatedSample("widgets", 3)))
                .build();

        assertEquals("{\"name\":\"widgets\"}", context.toString());
    }

    /** A field of type {@link EmbeddingVector} -- the exact shape of a woven class's cached internal
     *  state -- must never appear in the marshalled output unless explicitly annotated. This is the
     *  concrete proof that internal bookkeeping (a cached embedding, dirty-tracking state) can't leak into
     *  a prompt just because an object got wrapped in {@link ContextableObject}. */
    record WithEmbeddingVector(@dev.xtrafe.javai.annotations.PromptContext String title, EmbeddingVector vector) {
    }

    @Test
    void embeddingVectorFieldIsExcludedUnlessExplicitlyAnnotated() {
        PromptContext context = PromptContext.builder().build();
        EmbeddingVector vector = new EmbeddingVector(new float[] {1f, 2f, 3f}, "test-model", 3, java.time.Instant.now());

        String json = context.defaultMarshall(new WithEmbeddingVector("a real title", vector));

        assertEquals("{\"title\":\"a real title\"}", json);
        assertFalse(json.contains("dims"), "no trace of EmbeddingVector's own fields must leak through");
        assertFalse(json.contains("computedAt"));
    }

    @Test
    void customGsonEscapeHatchRestoresUnfilteredSerialization() {
        record Sample(String name, int count) {
        }
        PromptContext context = PromptContext.builder().gson(new com.google.gson.Gson()).build();

        String json = context.defaultMarshall(new Sample("widgets", 3));

        assertEquals("{\"name\":\"widgets\",\"count\":3}", json,
                "a caller-supplied plain Gson opts out of the default annotation filter entirely");
    }
}
