package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.testsupport.ScriptedEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ranked full-text search and hybrid search (OMI-642), one contract run against every backend: the behaviour must be
 * the same, though the mechanism is not (Postgres {@code tsvector} + {@code pg_trgm}, Neo4j's Lucene full-text index,
 * MongoDB {@code $text} + trigrams in a side collection).
 *
 * <p>The ruleset layout is the narrowing test, as {@link NarrowedVectorSearchTest}'s kinds are: every {@code core}
 * passage about grappling matches as well as the {@code supplement} ones, so asking for the best 2 {@code supplement}
 * matches must return 2 -- a search that ranked first and filtered after could return fewer.
 *
 * <p>The hybrid fixture: by meaning alone the storm is nearest; the young dragon is second by meaning and first in
 * the text ranking for "dragon" (it says it twice), so fused it comes first. Unscripted texts sit at 90° or more, so
 * nothing else is nearer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class FullTextSearchContract {

    static final String NEAR_ONLY = "A storm gathers over the mountain pass.";
    static final String WORDS_ONLY = "The dragon breathes fire in a cone sixty feet long.";
    static final String BOTH = "A young dragon circles above the pass; the dragon is hungry.";

    ScriptedEmbeddingProvider provider;
    TestPassageRepository passages;

    /** The backend under test. */
    abstract JavAIPersistenceConfig config();

    /** Whether this backend narrows a vector search (Neo4j refuses, OMI-230), and so a hybrid one. */
    abstract boolean narrowsVectorSearch();

    @BeforeAll
    void seed() {
        provider = new ScriptedEmbeddingProvider();
        provider.at(NEAR_ONLY, 1).at(BOTH, 25).at(WORDS_ONLY, 80);
        JavAIRuntime.configureEmbeddingProvider(provider);
        passages = JavAIPI.repository(TestPassageRepository.class, config());
        passages.save(new TestPassage("Grappling", "Grappling: to grapple, a character makes an unarmed attack roll; "
                + "grappled creatures cannot move. Grappling in armor is harder.", "core"));
        passages.save(new TestPassage("Overbearing", "Overbearing is grappling by weight: grapple the foe down.", "core"));
        passages.save(new TestPassage("Pummeling", "Pummeling and grappling are unarmed combat; grapples break on a 20.",
                "core"));
        passages.save(new TestPassage("Called shots", "A called shot to grapple a limb takes a -4 penalty.", "supplement"));
        passages.save(new TestPassage("Martial arts", "Martial artists may grapple once per round.", "supplement"));
        passages.save(new TestPassage("Glabrezu", "A tanar'ri with pincers and great magic resistance.", "monsters"));
        passages.save(new TestPassage("Glabrezu Lord", "A greater tanar'ri.", "monsters"));
        passages.save(new TestPassage("Gelatinous Cube", "A transparent cube that engulfs its prey.", "monsters"));
        passages.save(new TestPassage("Storm", NEAR_ONLY, "hybrid"));
        passages.save(new TestPassage("Dragon breath", WORDS_ONLY, "hybrid"));
        passages.save(new TestPassage("Young dragon", BOTH, "hybrid"));
    }

    static List<String> names(List<TestPassage> hits) {
        return hits.stream().map(TestPassage::getName).toList();
    }

    @Test
    void termsAreStemmedAndNonMatchesLeftOut() {
        List<TestPassage> hits = passages.matching("text").query("grappled").limit(10).results();
        assertEquals(5, hits.size(), "grappling, grapple, grapples, grappled: one stem -- " + names(hits));
        assertFalse(names(hits).contains("Glabrezu"));
    }

    @Test
    void hitsAreBestFirstWithPositiveScores() {
        List<Ranked<TestPassage>> hits = passages.matching("text").query("grapple").limit(5).ranked();
        assertEquals(5, hits.size());
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).similarity() >= hits.get(i).similarity(), "best first");
        }
        assertTrue(hits.get(hits.size() - 1).similarity() > 0);
    }

    @Test
    void everyWordIsRequired() {
        assertEquals(List.of("Called shots"),
                names(passages.matching("text").query("grapple penalty").limit(10).results()));
    }

    @Test
    void anExcludedWordLeavesItsPassagesOut() {
        List<String> hits = names(passages.matching("text").query("grapple -armor").limit(10).results());
        assertEquals(4, hits.size(), hits.toString());
        assertFalse(hits.contains("Grappling"));
    }

    @Test
    void aQuotedPhraseMatchesOnlyAsAPhrase() {
        assertEquals(List.of("Called shots"),
                names(passages.matching("text").query("\"called shot\"").limit(10).results()));
    }

    @Test
    void narrowingIsAppliedBeforeTheLimit() {
        List<TestPassage> hits = passages.matching("text").query("grapple")
                .where("ruleset").is("supplement").limit(2).results();
        assertEquals(2, hits.size(), "the best 2 supplement matches, whatever the core ones score");
        assertTrue(hits.stream().allMatch(p -> p.getRuleset().equals("supplement")));
    }

    @Test
    void pagingSkipsTheBestFirst() {
        List<TestPassage> all = passages.matching("text").query("grapple").limit(5).results();
        List<TestPassage> page = passages.matching("text").query("grapple").offset(2).limit(2).results();
        assertEquals(names(all).subList(2, 4), names(page));
    }

    @Test
    void aMisspelledNameIsFoundFuzzily() {
        List<Ranked<TestPassage>> hits = passages.matching("name").query("Glabreuz").fuzzy().limit(3).ranked();
        assertEquals("Glabrezu", hits.get(0).entity().getName(), "the closest spelling first");
        assertFalse(hits.stream().anyMatch(hit -> hit.entity().getName().equals("Gelatinous Cube")));
    }

    @Test
    void fuzzyNeedsAFieldDeclaredFuzzy() {
        assertThrows(IllegalArgumentException.class, () -> passages.matching("text").fuzzy());
    }

    @Test
    void onlyAFullTextFieldCanBeSearched() {
        assertThrows(IllegalArgumentException.class, () -> passages.matching("ruleset"));
    }

    @Test
    void anEditedTextIsSearchedAsItNowReads() {
        TestPassage passage = passages.save(new TestPassage("Edit me", "The lich phylactery is hidden.", "edits"));
        assertEquals(1, passages.matching("text").query("phylactery").limit(5).results().size());

        passage.setText("The vampire sleeps in its coffin.");
        passages.save(passage);

        assertTrue(passages.matching("text").query("phylactery").limit(5).results().isEmpty(),
                "the old words no longer match");
        assertEquals(List.of("Edit me"), names(passages.matching("text").query("coffin").limit(5).results()));
    }

    @Test
    void aDeletedEntityIsNoLongerFound() {
        TestPassage passage = passages.save(new TestPassage("Delete me", "The beholder floats.", "edits"));
        assertEquals(1, passages.matching("text").query("beholder").limit(5).results().size());
        passages.deleteById(passage.getId());
        assertTrue(passages.matching("text").query("beholder").limit(5).results().isEmpty());
    }

    @Test
    void aHybridSearchRanksWhatIsBothNearAndMatchingFirst() {
        List<Ranked<TestPassage>> byVector = passages.nearestBy("text").to(provider.reference()).limit(3).ranked();
        assertEquals("Storm", byVector.get(0).entity().getName(), "by meaning alone, the storm is nearest");

        List<Ranked<TestPassage>> hybrid = passages.nearestBy("text").to(provider.reference())
                .alsoMatching("text", "dragon").limit(3).ranked();

        assertEquals("Young dragon", hybrid.get(0).entity().getName(),
                "second by meaning and first by its words outranks first by meaning alone");
        assertEquals(3, hybrid.size());
        assertTrue(hybrid.get(0).similarity() > hybrid.get(1).similarity());
    }

    @Test
    void aNarrowedHybridSearchNarrowsBoth() {
        if (!narrowsVectorSearch()) {
            assertThrows(IllegalArgumentException.class, () -> passages.nearestBy("text").to(provider.reference())
                    .alsoMatching("text", "dragon").where("ruleset").is("hybrid").limit(3).ranked(),
                    "a backend that cannot narrow a vector search cannot narrow a hybrid one");
            return;
        }
        List<Ranked<TestPassage>> hybrid = passages.nearestBy("text").to(provider.reference())
                .alsoMatching("text", "grapple").where("ruleset").is("supplement").limit(5).ranked();
        assertEquals(2, hybrid.size(), "only supplement passages, from either ranking");
        assertTrue(hybrid.stream().allMatch(hit -> hit.entity().getRuleset().equals("supplement")));
    }

    @Test
    void aHybridSearchNeedsAFullTextField() {
        assertThrows(IllegalArgumentException.class,
                () -> passages.nearestBy("text").alsoMatching("ruleset", "x"));
    }

    // ---- the method-name form

    @Test
    void theMethodNameFormAnswersAsTheBuilderDoes() {
        assertEquals(names(passages.matching("text").query("grapple").limit(5).results()),
                names(passages.findMatchingByText("grapple", 5)));
    }

    @Test
    void theMethodNameFormNarrowsBeforeTheLimitAndKeepsScores() {
        List<Ranked<TestPassage>> hits = passages.findMatchingByTextAndRulesetIs("grapple", 2, "supplement");
        assertEquals(2, hits.size());
        assertTrue(hits.stream().allMatch(hit -> hit.entity().getRuleset().equals("supplement")
                && hit.similarity() > 0));
    }

    @Test
    void theMethodNameFormPagesAndSearchesFuzzily() {
        List<TestPassage> all = passages.findMatchingByText("grapple", 5);
        assertEquals(names(all).subList(2, 4),
                names(passages.findMatchingByText("grapple", org.springframework.data.domain.PageRequest.of(1, 2))));
        assertEquals("Glabrezu", passages.findSimilarByName("Glabreuz", org.springframework.data.domain.Limit.of(2))
                .get(0).getName());
    }

    @Test
    void anInvalidMethodNameFormIsRefusedWhenTheRepositoryIsCreated() {
        assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BadFullTextRepositories.FuzzyOnPlainField.class, config()));
        assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BadFullTextRepositories.NotAFullTextField.class, config()));
        assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BadFullTextRepositories.Unbounded.class, config()));
    }
}
