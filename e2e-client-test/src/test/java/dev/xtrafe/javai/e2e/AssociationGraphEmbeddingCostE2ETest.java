package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.assoc.AssocHub;
import dev.xtrafe.javai.e2e.domain.assoc.AssocLeaf;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OMI-187's invariant against the real association matrix, real Postgres, and a <b>real embedding model</b>:
 * saving and reading a whole object graph costs exactly one embedding call per distinct {@code @Vectorize}
 * field value, and nothing else is embedded at all.
 *
 * <h2>Why this exists on top of the unit tests</h2>
 *
 * Every other OMI-187 test runs against a fake provider, where an embedding is a hash and costs nothing.
 * That is what makes them fast and hermetic, and it is also what let the original defect hide: the fake
 * happens to map {@code ""} to the zero vector, so the wasted calls were invisible in their effect and only
 * countable. Here the provider is the one {@link JavAIEnvironment} configured -- a real model in the
 * monolithic container -- wrapped in a {@link RecordingEmbeddingProvider}. Every wasted call is a real
 * network round trip to a real model, which is the thing the ticket was actually about.
 *
 * <p>{@link AssociationGraphE2ETest} covers the same graph for correctness (OMI-161); this covers its cost.
 * The graph is deliberately the awkward one: eager and lazy singular associations, one-to-one, one-to-many,
 * many-to-many, a JavAI collection, and a non-vectorizable target -- the shapes most likely to make a walk
 * visit something twice.
 *
 * <p>Both halves of the ticket's ask are covered: cost when reading a field directly, and cost when
 * persisting -- under all three {@link EmbeddingConsistencyMode}s, since the modes differ in <em>when</em>
 * work happens and never in how much of it is necessary.
 */
class AssociationGraphEmbeddingCostE2ETest {

    private static final AtomicLong UNIQUE = new AtomicLong();

    private static RecordingEmbeddingProvider provider;
    private static EmbeddingConsistencyMode originalMode;

    @BeforeAll
    static void wrapTheRealProviderInARecorder() {
        JavAIEnvironment.ensureRunning();
        originalMode = JavAIRuntime.consistencyMode();
        // Wraps whatever JavAIEnvironment configured -- a real model against the monolithic container --
        // rather than substituting a fake, so these counts are real round trips.
        provider = new RecordingEmbeddingProvider(existingProvider());
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    /**
     * The provider {@link JavAIEnvironment}'s static initializer installed. Recovered by re-creating it the
     * same way rather than reading it back, since {@code JavAIRuntime.embeddingProvider()} is deliberately
     * package-private (only {@code currentModelId()} is exposed) -- see its javadoc.
     */
    private static JavAIEmbeddingProvider existingProvider() {
        return dev.xtrafe.javai.vector.LocalEmbeddingDefaults.create(
                dev.xtrafe.javai.e2e.environment.MonolithicContainer.embeddingEndpoint());
    }

    @AfterAll
    static void restore() {
        JavAIRuntime.configureConsistencyMode(originalMode);
        JavAIRuntime.configureEmbeddingProvider(existingProvider());
    }

    private static String unique(String label) {
        return label + "-" + UNIQUE.incrementAndGet();
    }

    /** The full matrix, built with globally-unique labels so text maps back to exactly one (object, field). */
    private static List<String> saveMatrixAndReturnExpectedTexts() {
        List<String> expected = new ArrayList<>();

        AssocLeaf mandatory = leaf("mandatory", expected);
        AssocLeaf lazyManyToOne = leaf("lazy-many-to-one", expected);
        AssocLeaf eagerManyToOne = leaf("eager-many-to-one", expected);
        AssocLeaf summaryTarget = leaf("summary-target", expected);

        AssocLeaf savedMandatory = JavAIEnvironment.postgresAssocLeafRepository().save(mandatory);
        JavAIEnvironment.postgresAssocLeafRepository().save(lazyManyToOne);
        JavAIEnvironment.postgresAssocLeafRepository().save(eagerManyToOne);
        JavAIEnvironment.postgresAssocLeafRepository().save(summaryTarget);

        String hubLabel = unique("cost-hub");
        expected.add(hubLabel);
        AssocHub hub = new AssocHub(hubLabel, savedMandatory);
        hub.setLazyManyToOne(lazyManyToOne);
        hub.setEagerManyToOne(eagerManyToOne);
        hub.setSummaryLazyManyToOne(summaryTarget);
        hub.setLazyOneToOne(leaf("lazy-one-to-one", expected));
        hub.setEagerOneToOne(leaf("eager-one-to-one", expected));
        hub.getLazyOneToMany().add(leaf("lazy-one-to-many", expected));
        hub.getLazyManyToMany().add(leaf("lazy-many-to-many", expected));
        hub.getSummaryJavAICollection().add(leaf("javai-collection", expected));

        JavAIEnvironment.postgresAssocHubRepository().save(hub);
        return expected;
    }

    private static AssocLeaf leaf(String name, List<String> expected) {
        String label = unique(name);
        expected.add(label);
        return new AssocLeaf(label);
    }

    @Test
    void persistingTheWholeAssociationMatrixEmbedsEachFieldExactlyOnce() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        provider.reset();

        List<String> expected = saveMatrixAndReturnExpectedTexts();

        provider.ledger().awaitQuiescence();
        provider.ledger().assertEmbeddedExactlyOnce(expected);
    }

    /**
     * The other half of the ask: cost when a caller simply reads vectors off the graph, with no persistence
     * in the measured window at all. Repeated reads must cost nothing beyond the first.
     *
     * <p>Deliberately reads {@code vector()}/{@code fieldVector()} and not {@code summaryVector()}: on a
     * detached entity whose {@code @Summary} child is an uninitialized proxy, summarizing throws
     * {@code LazyInitializationException} by design, which
     * {@code AssociationGraphE2ETest.summaryVectorOnADetachedEntityWithALazySummaryChildFailsLoudly} pins as
     * correct behaviour rather than a defect. Measuring cost is not the place to relitigate that.
     */
    @Test
    void readingFieldVectorsRepeatedlyCostsNothingAfterTheFirstRead() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        List<String> expected = saveMatrixAndReturnExpectedTexts();
        AssocHub hub = JavAIEnvironment.postgresAssocHubRepository()
                .findById(lastSavedHubId()).orElseThrow();

        // Cast because these entities are woven at LOAD time, exactly as a downstream consumer's are --
        // vector()/summaryVector() are not on the compile-time class, same as AssociationGraphE2ETest.
        JavAIVectorizable vectorizable = (JavAIVectorizable) hub;
        provider.reset();
        for (int i = 0; i < 5; i++) {
            vectorizable.vector();
            vectorizable.fieldVector("label");
        }
        provider.ledger().awaitQuiescence();

        assertEquals(0, provider.ledger().totalCalls(),
                "a graph loaded with its vectors already on file must cost nothing to read\n\n"
                        + provider.ledger().report() + "\n(graph had " + expected.size() + " vectorized fields)");
    }

    @ParameterizedTest
    @EnumSource(EmbeddingConsistencyMode.class)
    void persistingCostsOneEmbeddingPerFieldUnderEveryConsistencyMode(EmbeddingConsistencyMode mode) {
        JavAIRuntime.configureConsistencyMode(mode);
        provider.reset();

        List<String> expected = saveMatrixAndReturnExpectedTexts();

        // Under EVENTUAL/COALESCED a stale read returns immediately and recomputes off-thread, so the ledger
        // is not complete the instant the last save returns.
        provider.ledger().awaitQuiescence();
        provider.ledger().assertEmbeddedExactlyOnce(expected);
    }

    private static java.util.UUID lastSavedHubId() {
        List<AssocHub> all = JavAIEnvironment.postgresAssocHubRepository().findAll();
        return all.get(all.size() - 1).getId();
    }
}
