package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.EmbeddingLedger;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ExternalVector} -- a vector JavAI stores, versions and serves but never computes (OMI-290).
 *
 * <p>Every assertion here is made against a {@link RecordingEmbeddingProvider}, because the central claim
 * is about calls that must <b>not</b> happen. Asserting on JavAI's own dirty flags would let a buggy
 * implementation agree with the test; asserting at the provider cannot.
 */
class ExternalVectorTest {

    private RecordingEmbeddingProvider provider;
    private EmbeddingLedger ledger;

    @BeforeEach
    void configure() {
        provider = new RecordingEmbeddingProvider();
        ledger = provider.ledger();
        JavAIRuntime.configureEmbeddingProvider(provider);
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
    }

    @AfterEach
    void restoreDefaults() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
    }

    // ---- the supply lifecycle -----------------------------------------------------------------------

    @Test
    void isAbsentUntilSuppliedAndCostsNothingToAsk() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");

        assertTrue(node.externalVector("pixels").isAbsent());

        // The whole point: nothing in this process can produce this vector, so asking must not try. A
        // @Vectorize field's first read would block and call the provider here.
        ledger.awaitQuiescence();
        assertEquals(0, ledger.totalCalls(), "asking for an unsupplied external vector must reach no provider");
    }

    @Test
    void servesWhatWasSuppliedForTheContentItStillReferences() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        EmbeddingVector supplied = ExternalVectorNode.imageVector(0.25f);

        assertTrue(JavAIRuntime.supplyVector(node, "pixels", supplied, "sha256:aaa"));

        EmbeddingVector served = node.externalVector("pixels");
        assertFalse(served.isAbsent());
        assertArrayEquals(supplied.values(), served.values());
        assertEquals(ExternalVectorNode.IMAGE_MODEL, served.modelId());
        assertEquals(ExternalVectorNode.IMAGE_DIMS, served.dims());
    }

    @Test
    void aSupplyForContentTheObjectHasMovedOnFromIsDiscarded() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:new");

        // The producer embedded the old bytes and is only now getting round to reporting it -- the ordinary
        // outcome of at-least-once delivery racing an edit, so it is a false return rather than a throw.
        boolean stored = JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.5f),
                "sha256:old");

        assertFalse(stored);
        assertTrue(node.externalVector("pixels").isAbsent(), "a vector for other content must not be stored");
    }

    @Test
    void aLateSupplyDoesNotDisplaceTheCurrentOne() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:current");
        EmbeddingVector current = ExternalVectorNode.imageVector(0.25f);
        JavAIRuntime.supplyVector(node, "pixels", current, "sha256:current");

        assertFalse(JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.9f),
                "sha256:superseded"));

        assertArrayEquals(current.values(), node.externalVector("pixels").values(),
                "a discarded supply must leave the good vector in place");
    }

    @Test
    void becomesAbsentWhenTheContentItDescribesChanges() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:aaa");
        assertFalse(node.externalVector("pixels").isAbsent());

        node.setContentKey("sha256:bbb");

        // Not stale -- *wrong*: it is a confident description of content this object no longer references.
        // Missing beats wrong, and absence is a state every caller already handles.
        assertTrue(node.externalVector("pixels").isAbsent());
        ledger.awaitQuiescence();
        assertEquals(0, ledger.totalCalls(), "going absent must not trigger a recomputation attempt either");
    }

    @Test
    void becomesAbsentEvenWhenTheKeyIsWrittenBehindJavAIsBack() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:aaa");

        // A @Vectorize field written this way goes silently stale forever -- SPEC.md's "mutation rule" is
        // explicit that this is the accepted cost of not re-hashing content on every read. An external
        // vector has no such exposure, because its validity is re-derived from a short identifier rather
        // than tracked through an intercepted write.
        node.assignContentKeyBypassingTheSetter("sha256:ccc");

        assertTrue(node.externalVector("pixels").isAbsent());
    }

    @Test
    void isServedAgainIfTheObjectReturnsToTheContentItDescribes() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        EmbeddingVector supplied = ExternalVectorNode.imageVector(0.25f);
        JavAIRuntime.supplyVector(node, "pixels", supplied, "sha256:aaa");

        node.setContentKey("sha256:bbb");
        assertTrue(node.externalVector("pixels").isAbsent());
        node.setContentKey("sha256:aaa");

        // Falls out of comparing keys rather than tracking edits, and is correct rather than merely
        // convenient: the vector does describe this content, whatever route the object took back to it.
        assertArrayEquals(supplied.values(), node.externalVector("pixels").values());
    }

    // ---- refusals -----------------------------------------------------------------------------------

    @Test
    void refusesAVectorFromADifferentModelThanDeclared() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        EmbeddingVector wrongModel = new EmbeddingVector(new float[4], "some-other-model", 4,
                java.time.Instant.now());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIRuntime.supplyVector(node, "pixels", wrongModel, "sha256:aaa"));

        // Storage is partitioned by model, so this would file the vector in a table nothing queries.
        assertTrue(thrown.getMessage().contains("some-other-model"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(ExternalVectorNode.IMAGE_MODEL), thrown.getMessage());
    }

    @Test
    void refusesAnAbsentVectorRatherThanTreatingItAsAWithdrawal() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");

        // Absence is a derived state here ("nothing supplied, or superseded"), so accepting it as an input
        // would make a producer bug indistinguishable from a legitimately empty slot.
        assertThrows(IllegalArgumentException.class,
                () -> JavAIRuntime.supplyVector(node, "pixels", EmbeddingVector.absent(), "sha256:aaa"));
        assertThrows(IllegalArgumentException.class,
                () -> JavAIRuntime.supplyVector(node, "pixels", null, "sha256:aaa"));
    }

    @Test
    void refusesAnUndeclaredNameAndNamesTheDeclaredOnes() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> node.externalVector("nope"));

        assertTrue(thrown.getMessage().contains("nope"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("pixels"), "the message should name what is declared: "
                + thrown.getMessage());
    }

    @Test
    void aClassDeclaringNoneRejectsEveryName() {
        TestNode plain = new TestNode("no external vectors here");
        assertThrows(IllegalArgumentException.class, () -> JavAIRuntime.externalVector(plain, "pixels"));
    }

    // ---- coexistence with computed vectors ----------------------------------------------------------

    @Test
    void theDynamicFieldAccessorServesItToo() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        EmbeddingVector supplied = ExternalVectorNode.imageVector(0.25f);
        JavAIRuntime.supplyVector(node, "pixels", supplied, "sha256:aaa");

        // One name space, two resolutions -- reflective tooling should not need to know which kind it holds.
        assertArrayEquals(supplied.values(), node.fieldVector("pixels").values());
        assertEquals(0, countImageModelCalls(), "fieldVector must not have routed this through the provider");
    }

    @Test
    void doesNotDisturbTheComputedVectorsOnTheSameObject() {
        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:aaa");

        // 16-dimensional image vector alongside an 8-dimensional text one. Before OMI-290 this combination
        // was simply unrepresentable: any aggregate over both raises "cannot combine vectors of different
        // dimensionality" out of VectorMath, which is the first thing that happens when a second model
        // appears on one object.
        EmbeddingVector own = node.vector();
        assertFalse(own.isAbsent());
        assertEquals(8, own.dims(), "vector() is the caption's model, and only the caption's");
        assertFalse(node.summaryVector().isAbsent());
        assertEquals(8, node.summaryVector().dims());
    }

    @Test
    void isNotWarmedByPrecomputeVectors() {
        ExternalVectorNode node = new ExternalVectorNode("a unique caption for warming", "sha256:aaa");

        JavAIRuntime.precomputeVectors(List.of(node));
        ledger.awaitQuiescence();

        // The caption, and nothing else. A warm that reached the external vector would be asking the text
        // provider to embed a content hash.
        ledger.assertEmbeddedExactlyOnce("a unique caption for warming");
    }

    @Test
    void isCarriedAcrossAMergeTogetherWithItsContentKey() {
        ExternalVectorNode from = new ExternalVectorNode("a caption", "sha256:aaa");
        EmbeddingVector supplied = ExternalVectorNode.imageVector(0.25f);
        JavAIRuntime.supplyVector(from, "pixels", supplied, "sha256:aaa");
        ExternalVectorNode to = new ExternalVectorNode("a caption", "sha256:aaa");

        JavAIRuntime.transferComputedVectors(from, to);

        // Carrying the vector without its content key would leave the managed copy holding something it can
        // never serve -- indistinguishable, on every read, from a vector that had been superseded.
        assertFalse(to.externalVector("pixels").isAbsent(),
                "the content key must travel with the vector, or the merged copy serves absent forever");
        assertArrayEquals(supplied.values(), to.externalVector("pixels").values());
    }

    // ---- the consistency-mode claim -----------------------------------------------------------------

    @Nested
    class NeverBlocksAndNeverComputes {

        @ParameterizedTest
        @EnumSource(EmbeddingConsistencyMode.class)
        void underEveryConsistencyMode(EmbeddingConsistencyMode mode) {
            JavAIRuntime.configureConsistencyMode(mode);
            ExternalVectorNode node = new ExternalVectorNode("caption for " + mode, "sha256:aaa");

            assertTrue(node.externalVector("pixels").isAbsent());
            JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:aaa");
            assertFalse(node.externalVector("pixels").isAbsent());
            node.setContentKey("sha256:bbb");
            assertTrue(node.externalVector("pixels").isAbsent());

            ledger.awaitQuiescence();
            assertEquals(0, ledger.totalCalls(),
                    "no mode may turn an external vector read into a provider call -- got: " + ledger.report());
        }

        @ParameterizedTest
        @EnumSource(EmbeddingConsistencyMode.class)
        void andInsideAPersistenceFlushWhichOverridesEveryMode(EmbeddingConsistencyMode mode) {
            JavAIRuntime.configureConsistencyMode(mode);
            ExternalVectorNode node = new ExternalVectorNode("flushed caption for " + mode, "sha256:aaa");
            JavAIRuntime.supplyVector(node, "pixels", ExternalVectorNode.imageVector(0.25f), "sha256:aaa");
            ledger.reset();

            // runWithSubgraphLockedForPersistence forces every read on this thread to compute accurately,
            // overriding the configured mode -- which for an external vector would mean waiting on a
            // provider that could never produce it, and asking the *text* provider at that. This is the
            // case "external vectors are always EVENTUAL_CONSISTENCY" would not have covered.
            JavAIRuntime.runWithSubgraphLockedForPersistence(node, () -> {
                assertFalse(node.externalVector("pixels").isAbsent());
                assertFalse(node.fieldVector("pixels").isAbsent());
            });

            ledger.awaitQuiescence();
            assertEquals(0, countImageModelCallsIn(ledger),
                    "a flush must not try to compute an external vector -- got: " + ledger.report());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** Calls whose text looks like a content key rather than real field text -- always zero if correct. */
    private int countImageModelCalls() {
        return countImageModelCallsIn(ledger);
    }

    private static int countImageModelCallsIn(EmbeddingLedger ledger) {
        int count = 0;
        for (EmbeddingLedger.Call call : ledger.calls()) {
            if (call.text().startsWith("sha256:")) {
                count++;
            }
        }
        return count;
    }

    @Test
    void theRuntimeReportsWhatAClassDeclares() {
        assertEquals(List.of("pixels"), JavAIRuntime.externalVectorNames(ExternalVectorNode.class));
        assertEquals(ExternalVectorNode.IMAGE_MODEL,
                JavAIRuntime.externalVectorModel(ExternalVectorNode.class, "pixels"));
        assertTrue(JavAIRuntime.isExternalVectorName(ExternalVectorNode.class, "pixels"));
        assertFalse(JavAIRuntime.isExternalVectorName(ExternalVectorNode.class, "caption"));
        assertFalse(JavAIRuntime.isExternalVectorName(TestNode.class, "pixels"));

        ExternalVectorNode node = new ExternalVectorNode("a caption", "sha256:aaa");
        assertEquals("sha256:aaa", JavAIRuntime.externalVectorKey(node, "pixels"));
        node.setContentKey("sha256:bbb");
        assertEquals("sha256:bbb", JavAIRuntime.externalVectorKey(node, "pixels"),
                "the key is read live, so persistence always writes what the object references now");
        assertSame(EmbeddingVector.absent(), node.externalVector("pixels"));
    }
}
