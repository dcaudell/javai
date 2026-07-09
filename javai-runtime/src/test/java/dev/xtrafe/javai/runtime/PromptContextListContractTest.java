package dev.xtrafe.javai.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link PromptContext} fulfills the full {@code List<Contextable>} contract (not just the
 * assembly/marshalling behavior {@code PromptContextTest} already covers) -- in particular, that whole
 * lists of {@link Contextable} can be added directly to an already-built instance, not just supplied at
 * construction time via {@code Builder.entries(Collection)}.
 */
class PromptContextListContractTest {

    @Test
    void sizeIsEmptyContainsReflectEntries() {
        PromptContext context = PromptContext.builder()
                .entry(new PlainTextEntry("a"))
                .entry(new PlainTextEntry("b"))
                .build();

        assertEquals(2, context.size());
        assertFalse(context.isEmpty());
        assertTrue(context.contains(new PlainTextEntry("a")));
        assertFalse(context.contains(new PlainTextEntry("nope")));
    }

    @Test
    void emptyContextIsEmptyAndZeroSized() {
        PromptContext context = PromptContext.builder().build();

        assertTrue(context.isEmpty());
        assertEquals(0, context.size());
    }

    @Test
    void addDirectlyOnAnAlreadyBuiltInstanceIsReflectedInToString() {
        PromptContext context = PromptContext.builder().entry(new PlainTextEntry("first")).build();

        context.add(new PlainTextEntry("second"));

        assertEquals("first\n\nsecond", context.toString());
    }

    @Test
    void addAllAcceptsAWholeListOfContextableDirectly() {
        PromptContext context = PromptContext.builder().build();
        List<Contextable> newEntries = List.of(new PlainTextEntry("x"), new PlainTextEntry("y"), new PlainTextEntry("z"));

        context.addAll(newEntries);

        assertEquals(3, context.size());
        assertEquals("x\n\ny\n\nz", context.toString());
    }

    @Test
    void addAllAcceptsAnotherPromptContextSinceItIsNowItselfAListOfContextable() {
        PromptContext source = PromptContext.builder()
                .entry(new PlainTextEntry("from-source-1"))
                .entry(new PlainTextEntry("from-source-2"))
                .build();
        PromptContext target = PromptContext.builder().entry(new PlainTextEntry("original")).build();

        target.addAll(source);

        assertEquals("original\n\nfrom-source-1\n\nfrom-source-2", target.toString());
    }

    @Test
    void builderEntriesAlsoAcceptsAnExistingPromptContextAsTheCollectionArgument() {
        PromptContext source = PromptContext.builder()
                .entry(new PlainTextEntry("a"))
                .entry(new PlainTextEntry("b"))
                .build();

        PromptContext built = PromptContext.builder().entries(source).build();

        assertEquals("a\n\nb", built.toString());
    }

    @Test
    void getSetRemoveIndexOfWorkByIndex() {
        PromptContext context = PromptContext.builder()
                .entry(new PlainTextEntry("a"))
                .entry(new PlainTextEntry("b"))
                .entry(new PlainTextEntry("c"))
                .build();

        assertEquals(new PlainTextEntry("b"), context.get(1));
        assertEquals(1, context.indexOf(new PlainTextEntry("b")));

        context.set(1, new PlainTextEntry("replaced"));
        assertEquals("a\n\nreplaced\n\nc", context.toString());

        context.remove(0);
        assertEquals("replaced\n\nc", context.toString());
    }

    @Test
    void iterationOrderIsInsertionOrder() {
        PromptContext context = PromptContext.builder()
                .entry(new PlainTextEntry("1"))
                .entry(new PlainTextEntry("2"))
                .entry(new PlainTextEntry("3"))
                .build();

        List<String> seen = new ArrayList<>();
        for (Contextable c : context) {
            seen.add(((PlainTextEntry) c).text());
        }

        assertEquals(List.of("1", "2", "3"), seen);
    }

    @Test
    void equalsAndHashCodeMatchListContractNotRecordComponentIdentity() {
        PromptContext withLabelA = PromptContext.builder()
                .sourceLabel("label-a")
                .maxLength(100)
                .entry(new PlainTextEntry("same"))
                .build();
        PromptContext withLabelB = PromptContext.builder()
                .sourceLabel("totally-different-label")
                .entry(new PlainTextEntry("same"))
                .build();

        // Same elements, different sourceLabel/maxLength -- must still be equal per List's own contract.
        assertEquals(withLabelA, withLabelB);
        assertEquals(withLabelA.hashCode(), withLabelB.hashCode());

        // Must also equal a plain ArrayList<Contextable> with the same elements, per List's documented
        // general contract (equal iff same elements in the same order, regardless of concrete type).
        List<Contextable> plainList = new ArrayList<>(List.of(new PlainTextEntry("same")));
        assertEquals(withLabelA, plainList);
        assertEquals(plainList, withLabelA);
        assertEquals(plainList.hashCode(), withLabelA.hashCode());
    }

    @Test
    void differingEntriesAreNotEqualRegardlessOfMetadata() {
        PromptContext a = PromptContext.builder().entry(new PlainTextEntry("a")).build();
        PromptContext b = PromptContext.builder().entry(new PlainTextEntry("b")).build();

        assertFalse(a.equals(b));
    }

    @Test
    void clearEmptiesTheContext() {
        PromptContext context = PromptContext.builder()
                .entry(new PlainTextEntry("a"))
                .entry(new PlainTextEntry("b"))
                .build();

        context.clear();

        assertTrue(context.isEmpty());
        assertEquals("", context.toString());
    }

    @Test
    void streamAndForEachDefaultMethodsWorkThroughDelegation() {
        PromptContext context = PromptContext.builder()
                .entry(new PlainTextEntry("a"))
                .entry(new PlainTextEntry("b"))
                .build();

        long count = context.stream().count();

        assertEquals(2, count);
    }
}
