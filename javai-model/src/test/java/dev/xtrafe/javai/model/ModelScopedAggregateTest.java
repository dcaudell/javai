package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.EmbeddingLedger;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vector(modelId)} / {@code summaryVector(modelId)} -- the aggregates that can name which embedding
 * model they are about (OMI-290).
 *
 * <p>The unqualified forms are unchanged, and several assertions here exist to pin exactly that: this is an
 * additive capability, not a redefinition, and Vector Core is the costliest place in the repository to
 * quietly change an answer.
 */
class ModelScopedAggregateTest {

    private RecordingEmbeddingProvider provider;
    private EmbeddingLedger ledger;

    @BeforeEach
    void configure() {
        provider = new RecordingEmbeddingProvider();
        ledger = provider.ledger();
        JavAIRuntime.configureEmbeddingProvider(provider);
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
    }

    @Test
    void namingTheTextModelGivesTheSameAnswerAsTheUnqualifiedForm() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:aaa");

        assertArrayEquals(node.vector().values(), node.vector(FakeEmbeddingProvider.MODEL_ID).values(),
                "asking for the configured model must be the same question vector() already answers");
    }

    @Test
    void namingTheImageModelGivesTheSuppliedVector() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        EmbeddingVector supplied = ExternalVectorNode.imageVector(0.25f);
        JavAIRuntime.supplyVector(node, "pixels", supplied, "sha256:aaa");

        EmbeddingVector scoped = node.vector(ExternalVectorNode.IMAGE_MODEL);
        assertEquals(ExternalVectorNode.IMAGE_DIMS, scoped.dims());
        assertArrayEquals(supplied.values(), scoped.values());
    }

    @Test
    void namingTheImageModelDoesNotEmbedTheText() {
        ExternalVectorNode node = new ExternalVectorNode("a caption nobody asked about", "sha256:aaa");
        JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:aaa");
        ledger.reset();

        node.vector(ExternalVectorNode.IMAGE_MODEL);
        node.summaryVector(ExternalVectorNode.IMAGE_MODEL);

        ledger.awaitQuiescence();
        // A @Vectorize field's model is whatever the provider is, so when that is not the model being
        // asked for the fields must be skipped *without being read*. Reading them to discover they do not
        // match would embed the caption in order to throw it away -- the exact waste OMI-187 removed.
        assertEquals(0, ledger.totalCalls(),
                "asking for another model's aggregate must not compute this one's: " + ledger.report());
    }

    @Test
    void namingAModelTheObjectCarriesNothingFromIsAbsent() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        assertTrue(node.vector("a-model-nobody-here-uses").isAbsent());
        assertTrue(node.summaryVector("a-model-nobody-here-uses").isAbsent());
    }

    @Test
    void aNullModelIsAbsentRatherThanAnError() {
        // currentModelId() answers null for a provider that cannot name its model, and that answer flows
        // straight into these methods from callers that did not think to check.
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        assertTrue(node.vector(null).isAbsent());
    }

    // ---- aggregation across a container --------------------------------------------------------------

    @Test
    void aContainerSummarizesItsSubtreeSeparatelyPerModel() {
        ExternalVectorAlbum album = new ExternalVectorAlbum("holiday");
        ExternalVectorNode first = new ExternalVectorNode("on the beach", "sha256:one");
        ExternalVectorNode second = new ExternalVectorNode("in the mountains", "sha256:two");
        album.getImages().add(first);
        album.getImages().add(second);
        JavAIRuntime.supplyVector(first, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:one");
        JavAIRuntime.supplyVector(second, "pixels", ExternalVectorNode.imageVector(0.75f), "sha256:two");

        EmbeddingVector textSummary = album.summaryVector();
        EmbeddingVector imageSummary = album.summaryVector(ExternalVectorNode.IMAGE_MODEL);

        // Two coherent aggregates over one subtree: what it reads like, and what it looks like. Neither is
        // computable from the other, and combining them is not a weaker answer but no answer at all.
        assertEquals(FakeEmbeddingProvider.DIMS, textSummary.dims());
        assertEquals(ExternalVectorNode.IMAGE_DIMS, imageSummary.dims());
        assertFalse(imageSummary.isAbsent());
    }

    @Test
    void theImageSummaryRespondsToItsMembersRatherThanBeingAConstant() {
        ExternalVectorAlbum beaches = new ExternalVectorAlbum("beaches");
        ExternalVectorNode beach = new ExternalVectorNode("on the beach", "sha256:one");
        beaches.getImages().add(beach);
        JavAIRuntime.supplyVector(beach, "pixels", ExternalVectorNode.imageVector(0.1f), "sha256:one");

        ExternalVectorAlbum mountains = new ExternalVectorAlbum("mountains");
        ExternalVectorNode mountain = new ExternalVectorNode("in the mountains", "sha256:two");
        mountains.getImages().add(mountain);
        JavAIRuntime.supplyVector(mountain, "pixels", ExternalVectorNode.imageVector(0.9f), "sha256:two");

        assertNotEquals(
                beaches.summaryVector(ExternalVectorNode.IMAGE_MODEL).values()[0],
                mountains.summaryVector(ExternalVectorNode.IMAGE_MODEL).values()[0],
                "two albums of different-looking images must not summarize to the same vector");
    }

    @Test
    void aMemberWithNoVectorInThatModelContributesNothingRatherThanBreakingTheAggregate() {
        ExternalVectorAlbum album = new ExternalVectorAlbum("mixed");
        ExternalVectorNode processed = new ExternalVectorNode("already processed", "sha256:one");
        ExternalVectorNode pending = new ExternalVectorNode("still in the queue", "sha256:two");
        album.getImages().add(processed);
        album.getImages().add(pending);
        JavAIRuntime.supplyVector(processed, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:one");

        // The ordinary steady state of an asynchronous pipeline: some assets embedded, some not yet. The
        // aggregate must be over what exists, not absent because something is missing nor an error.
        EmbeddingVector imageSummary = album.summaryVector(ExternalVectorNode.IMAGE_MODEL);
        assertFalse(imageSummary.isAbsent());
        assertEquals(ExternalVectorNode.IMAGE_DIMS, imageSummary.dims());
    }

    @Test
    void anEmptyContainerIsAbsentInEveryModel() {
        ExternalVectorAlbum album = new ExternalVectorAlbum("empty");
        assertTrue(album.getImages().vector(ExternalVectorNode.IMAGE_MODEL).isAbsent());
        assertTrue(album.getImages().summaryVector(ExternalVectorNode.IMAGE_MODEL).isAbsent());
    }

    @Test
    void theCollectionAggregatesItsMembersPerModelDirectly() {
        JavAIArrayList<ExternalVectorNode> images = new JavAIArrayList<>();
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        EmbeddingVector supplied = ExternalVectorNode.imageVector(0.4f);
        images.add(node);
        JavAIRuntime.supplyVector(node, "pixels", supplied, "sha256:aaa");

        assertArrayEquals(supplied.values(), images.vector(ExternalVectorNode.IMAGE_MODEL).values(),
                "a single-member centroid is that member's own vector");
    }

    // ---- the unqualified forms must not have moved ---------------------------------------------------

    @Test
    void theUnqualifiedSummaryStillCachesAndStillClearsItsFlags() {
        ExternalVectorAlbum album = new ExternalVectorAlbum("holiday");
        ExternalVectorNode child = new ExternalVectorNode("on the beach", "sha256:one");
        album.getImages().add(child);
        JavAIRuntime.supplyVector(child, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:one");

        EmbeddingVector first = album.summaryVector();
        // Interleaving a model-scoped read must not disturb the cached unqualified one -- the scoped form
        // is deliberately uncached precisely so it needs no slot of its own to collide over.
        album.summaryVector(ExternalVectorNode.IMAGE_MODEL);
        EmbeddingVector second = album.summaryVector();

        assertArrayEquals(first.values(), second.values());
        assertFalse(album.isSummaryDirty(), "the unqualified summary still clears its own flag");
    }

    @Test
    void aCycleTerminatesInTheModelScopedFormToo() {
        ExternalVectorAlbum album = new ExternalVectorAlbum("self-referential");
        ExternalVectorNode child = new ExternalVectorNode("a caption", "sha256:aaa");
        album.getImages().add(child);
        JavAIRuntime.supplyVector(child, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:aaa");
        // The collection is a node in its own right, so adding it to itself is a genuine cycle through the
        // same recursion the unqualified form guards.
        album.getImages().add(child);

        assertFalse(album.summaryVector(ExternalVectorNode.IMAGE_MODEL).isAbsent());
        assertFalse(album.summaryVector().isAbsent());
    }
}
