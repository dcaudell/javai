package dev.xtrafe.javai.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Common-combination coverage beyond each collection type's own dedicated test class: a
 * {@link PromptContext} whose entries mix plain text, wrapped POJOs, and a nested JavAI collection, plus a
 * JavAI collection used directly as one {@link PromptContext} entry -- proving {@code @PromptContext}
 * field filtering and per-element {@link Contextable} delegation compose correctly, not just in isolation.
 */
class ContextableCollectionsTest {

    private record Product(@dev.xtrafe.javai.annotations.PromptContext String name, double internalCostBasis) {
    }

    @Test
    void promptContextEntriesCanMixPlainTextWrappedPojosAndACollection() {
        JavAILinkedHashMap<String, Contextable> priceList = new JavAILinkedHashMap<>();
        priceList.put("sku-1", new ContextableObject<>(new Product("Widget", 4.20)));
        priceList.put("sku-2", new ContextableObject<>(new Product("Gadget", 7.10)));

        PromptContext context = PromptContext.builder()
                .entry(new PlainTextEntry("Available products:"))
                .entry(new ContextableObject<>(new Product("Standalone Gizmo", 1.00)))
                .entry(priceList)
                .build();

        String rendered = context.toString();
        assertEquals(
                "Available products:\n\n"
                        + "{\"name\":\"Standalone Gizmo\"}\n\n"
                        + "{\"name\":\"Widget\"}\n\n{\"name\":\"Gadget\"}",
                rendered);
        assertFalse(rendered.contains("internalCostBasis"), "unannotated fields never leak through any layer");
        assertFalse(rendered.contains("sku-"), "map keys never leak through -- only values render");
    }

    @Test
    void aJavaiListOfJavaiMapsRendersEachMapsValuesInTurn() {
        JavAILinkedHashMap<String, Contextable> first = new JavAILinkedHashMap<>();
        first.put("a", new ContextableObject<>(new Product("Widget", 4.20)));
        JavAILinkedHashMap<String, Contextable> second = new JavAILinkedHashMap<>();
        second.put("b", new ContextableObject<>(new Product("Gadget", 7.10)));

        JavAIArrayList<Contextable> listOfMaps = new JavAIArrayList<>();
        listOfMaps.add(first);
        listOfMaps.add(second);

        String rendered = listOfMaps.toContext(PromptContext.builder().build());

        assertEquals("{\"name\":\"Widget\"}\n\n{\"name\":\"Gadget\"}", rendered);
    }

    @Test
    void nestedPromptContextUsedAsAnEntryStillEnforcesItsOwnBudgetInsideAParent() {
        PromptContext inner = PromptContext.builder()
                .maxLength(5)
                .entry(new PlainTextEntry("way too long to fit in five characters"))
                .build();

        PromptContext outer = PromptContext.builder()
                .entry(new PlainTextEntry("intro"))
                .entry(inner)
                .build();

        // inner renders as an empty string (its own 5-char budget excludes its only entry), but the
        // outer's own unbounded assembly still includes that empty result as "a" rendered entry
        assertEquals("intro\n\n", outer.toString());
    }
}
