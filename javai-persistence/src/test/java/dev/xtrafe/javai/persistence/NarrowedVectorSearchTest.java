package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.testsupport.ScriptedEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;

import static dev.xtrafe.javai.persistence.TestAsset.Kind.AUDIO;
import static dev.xtrafe.javai.persistence.TestAsset.Kind.IMAGE;
import static dev.xtrafe.javai.persistence.TestAsset.Kind.SHORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vector search combined with a relational predicate, in both idioms (OMI-230).
 *
 * <h2>The fixture, and why it is laid out this way</h2>
 *
 * Ten assets at known angles from the reference vector, with the <b>five nearest all {@code AUDIO}</b> and
 * the {@code IMAGE}/{@code SHORT} ones deliberately further away. That arrangement is the entire test
 * design, because it is what separates a real narrowed search from the over-fetch-and-discard it replaces:
 *
 * <ul>
 *   <li>Asking for the nearest <b>3 {@code IMAGE}</b> assets must return <b>3</b>. A backend that ranked
 *       first and filtered afterwards would return <b>none</b> -- the top 3 overall are all {@code AUDIO}.</li>
 *   <li>So an assertion of "everything returned is an IMAGE" is not enough on its own, and neither is
 *       "something came back". The <em>count</em> is what proves the limit was applied after the predicate,
 *       and the count is asserted everywhere below.</li>
 * </ul>
 *
 * <p>Angles come from {@link ScriptedEmbeddingProvider}, so each hit's similarity is {@code cos θ} in closed
 * form. That pins the second half of the contract: a backend that forgot to convert its own store's score
 * into JavAI's cosine convention still returns a plausible ordering, and only the value catches it.
 */
@Testcontainers
class NarrowedVectorSearchTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static ScriptedEmbeddingProvider provider;
    private static JavAIPersistenceConfig config;
    private static TestAssetRepository assets;

    /** Angle from the reference, in degrees. The five nearest are all AUDIO, on purpose. */
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("audio nearest",   2,  AUDIO, true,  5, "ana"),
            new Fixture("audio second",    4,  AUDIO, false, 4, "ben"),
            new Fixture("audio third",     6,  AUDIO, true,  3, null),
            new Fixture("audio fourth",    8,  AUDIO, false, 2, "cara"),
            new Fixture("audio fifth",    10,  AUDIO, true,  1, null),
            new Fixture("image nearest",  20,  IMAGE, true,  5, "ana"),
            new Fixture("image second",   30,  IMAGE, false, 4, null),
            new Fixture("image third",    40,  IMAGE, true,  3, "ben"),
            new Fixture("short nearest",  50,  SHORT, true,  2, "cara"),
            new Fixture("short second",   60,  SHORT, false, 1, null));

    private record Fixture(String caption, double degrees, TestAsset.Kind kind, boolean published,
            int rating, String owner) {
    }

    @BeforeAll
    static void seed() {
        provider = new ScriptedEmbeddingProvider();
        for (Fixture fixture : FIXTURES) {
            provider.at(fixture.caption(), fixture.degrees());
        }
        JavAIRuntime.configureEmbeddingProvider(provider);
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        assets = JavAIPI.repository(TestAssetRepository.class, config);
        for (Fixture fixture : FIXTURES) {
            assets.save(new TestAsset(fixture.caption(), fixture.kind(), fixture.published(),
                    fixture.rating(), fixture.owner()));
        }
    }

    private static EmbeddingVector reference() {
        return provider.reference();
    }

    private static List<String> captions(List<TestAsset> hits) {
        return hits.stream().map(TestAsset::getCaption).toList();
    }

    private static List<String> rankedCaptions(List<Ranked<TestAsset>> hits) {
        return hits.stream().map(hit -> hit.entity().getCaption()).toList();
    }

    // ================================================================================================
    // The method-name idiom
    // ================================================================================================

    @Nested
    @DisplayName("the findNearestBy…VectorAnd<Predicate> method-name idiom")
    class MethodNameIdiom {

        /** The pre-OMI-230 shape must be untouched: no predicate, plain entities, nearest first. */
        @Test
        @DisplayName("an unnarrowed search still ranks by similarity, nearest first")
        void unnarrowedSearchStillWorks() {
            assertEquals(List.of("audio nearest", "audio second", "audio third"),
                    captions(assets.findNearestByCaptionVector(reference(), 3)));
        }

        /**
         * The ticket, in one assertion. The three nearest assets overall are all AUDIO, so a backend that
         * ranked before it filtered would answer this with an empty list.
         */
        @Test
        @DisplayName("the limit applies AFTER the predicate, not before")
        void limitAppliesAfterThePredicate() {
            List<TestAsset> hits = assets.findNearestByCaptionVectorAndKindIs(reference(), 3, IMAGE);

            assertEquals(3, hits.size(), "asking for the nearest 3 IMAGEs must yield 3 of them -- getting "
                    + "fewer means the predicate was applied to an already-limited ranking, which is the "
                    + "over-fetch-and-discard this feature exists to remove");
            assertEquals(List.of("image nearest", "image second", "image third"), captions(hits),
                    "and they must still be in nearest-first order among themselves");
        }

        @Test
        @DisplayName("In narrows to a set of values")
        void inNarrowsToASet() {
            List<TestAsset> hits = assets.findNearestByCaptionVectorAndKindIn(reference(), 4, Set.of(IMAGE, SHORT));

            assertEquals(List.of("image nearest", "image second", "image third", "short nearest"),
                    captions(hits));
        }

        @Test
        @DisplayName("a boolean predicate narrows")
        void booleanNarrows() {
            List<TestAsset> hits = assets.findNearestByCaptionVectorAndPublishedTrue(reference(), 3);

            assertEquals(List.of("audio nearest", "audio third", "audio fifth"), captions(hits));
        }

        @Test
        @DisplayName("a comparison predicate narrows")
        void comparisonNarrows() {
            List<TestAsset> hits = assets.findNearestByCaptionVectorAndRatingGreaterThan(reference(), 10, 4);

            assertEquals(List.of("audio nearest", "image nearest"), captions(hits));
        }

        @Test
        @DisplayName("Between narrows on a range")
        void betweenNarrows() {
            List<TestAsset> hits = assets.findNearestByCaptionVectorAndRatingBetween(reference(), 3, 4, 5);

            assertEquals(List.of("audio nearest", "audio second", "image nearest"), captions(hits));
        }

        @Test
        @DisplayName("IsNull and IsNotNull narrow, and partition the fixture between them")
        void nullnessNarrows() {
            List<String> withoutOwner = captions(assets.findNearestByCaptionVectorAndOwnerIsNull(reference(), 10));
            List<String> withOwner = captions(assets.findNearestByCaptionVectorAndOwnerIsNotNull(reference(), 10));

            assertEquals(List.of("audio third", "audio fifth", "image second", "short second"), withoutOwner);
            assertEquals(6, withOwner.size());
            assertTrue(java.util.Collections.disjoint(withoutOwner, withOwner),
                    "the two halves must not overlap");
            assertEquals(FIXTURES.size(), withoutOwner.size() + withOwner.size(),
                    "and together they must account for every asset");
        }

        @Test
        @DisplayName("a string-matching predicate narrows")
        void containingNarrows() {
            List<TestAsset> hits = assets.findNearestByCaptionVectorAndOwnerContaining(reference(), 10, "an");

            assertEquals(List.of("audio nearest", "image nearest"), captions(hits), "matches 'ana' only");
        }

        @Test
        @DisplayName("two AND-ed conditions both apply")
        void andComposes() {
            List<TestAsset> hits =
                    assets.findNearestByCaptionVectorAndKindIsAndPublishedTrue(reference(), 10, IMAGE);

            assertEquals(List.of("image nearest", "image third"), captions(hits));
        }

        @Test
        @DisplayName("Or widens across groups")
        void orWidens() {
            List<TestAsset> hits =
                    assets.findNearestByCaptionVectorAndKindIsOrRatingGreaterThan(reference(), 10, SHORT, 4);

            assertEquals(List.of("audio nearest", "image nearest", "short nearest", "short second"),
                    captions(hits), "every SHORT, plus everything rated above 4, ranked together");
        }

        @Test
        @DisplayName("nothing satisfying the predicate yields nothing, rather than the nearest unfiltered")
        void anUnsatisfiablePredicateYieldsNothing() {
            assertEquals(List.of(),
                    assets.findNearestByCaptionVectorAndRatingGreaterThan(reference(), 10, 99));
        }

        // ---- ranked returns -------------------------------------------------------------------------

        @Test
        @DisplayName("a List<Ranked<T>> return carries each hit's cosine similarity")
        void rankedReturnCarriesSimilarity() {
            List<Ranked<TestAsset>> hits =
                    assets.findNearestByCaptionVectorAndKindIs(reference(), IMAGE, Limit.of(3));

            assertEquals(List.of("image nearest", "image second", "image third"), rankedCaptions(hits));
            // cos 20°, cos 30°, cos 40° -- the closed form the scripted provider guarantees. This is what
            // catches a backend that returned its store's own score without converting it.
            assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(20), hits.get(0).similarity(), 1e-4);
            assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(30), hits.get(1).similarity(), 1e-4);
            assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(40), hits.get(2).similarity(), 1e-4);
        }

        @Test
        @DisplayName("similarity decreases down the ranking, and distance is its complement")
        void similarityIsMonotonic() {
            List<Ranked<TestAsset>> hits = assets.findNearestBySummaryVector(reference(), 5);

            for (int i = 1; i < hits.size(); i++) {
                assertTrue(hits.get(i - 1).similarity() >= hits.get(i).similarity(),
                        "hit " + i + " must not be nearer than the one before it");
            }
            assertEquals(1.0 - hits.get(0).similarity(), hits.get(0).distance(), 1e-9);
        }

        // ---- paging ---------------------------------------------------------------------------------

        @Test
        @DisplayName("a Pageable pages an unnarrowed search")
        void pageablePages() {
            List<TestAsset> firstPage = assets.findNearestByCaptionVector(reference(), PageRequest.of(0, 3));
            List<TestAsset> secondPage = assets.findNearestByCaptionVector(reference(), PageRequest.of(1, 3));

            assertEquals(List.of("audio nearest", "audio second", "audio third"), captions(firstPage));
            assertEquals(List.of("audio fourth", "audio fifth", "image nearest"), captions(secondPage));
        }

        @Test
        @DisplayName("a Pageable pages a narrowed search, the offset counting only matches")
        void pageablePagesANarrowedSearch() {
            List<TestAsset> secondPage =
                    assets.findNearestByCaptionVectorAndKindIs(reference(), IMAGE, PageRequest.of(1, 2));

            assertEquals(List.of("image third"), captions(secondPage),
                    "page 2 of the IMAGEs is the third IMAGE -- the offset must skip matches, not raw hits");
        }

        @Test
        @DisplayName("an offset past the end yields nothing rather than failing")
        void offsetPastTheEndIsEmpty() {
            assertEquals(List.of(),
                    captions(assets.findNearestByCaptionVector(reference(), PageRequest.of(50, 3))));
        }
    }

    // ================================================================================================
    // The builder idiom -- every case above has a counterpart here, because the two must agree
    // ================================================================================================

    @Nested
    @DisplayName("the NearestQuery builder idiom")
    class BuilderIdiom {

        @Test
        @DisplayName("the limit applies AFTER the predicate here too")
        void limitAppliesAfterThePredicate() {
            List<TestAsset> hits = assets.nearestBy("caption")
                    .to(reference())
                    .where("kind").is(IMAGE)
                    .limit(3)
                    .results();

            assertEquals(3, hits.size());
            assertEquals(List.of("image nearest", "image second", "image third"), captions(hits));
        }

        /** The two idioms are one mechanism, so the same question must produce the same answer -- asserted
         *  directly rather than inferred from the two suites passing separately. */
        @Test
        @DisplayName("the builder and the method name answer identically")
        void bothIdiomsAgree() {
            List<String> viaMethodName =
                    captions(assets.findNearestByCaptionVectorAndKindIn(reference(), 4, Set.of(IMAGE, SHORT)));
            List<String> viaBuilder = captions(assets.nearestBy("caption")
                    .to(reference())
                    .where("kind").in(IMAGE, SHORT)
                    .limit(4)
                    .results());

            assertEquals(viaMethodName, viaBuilder);
        }

        @Test
        @DisplayName("conditions AND together")
        void conditionsAnd() {
            List<TestAsset> hits = assets.nearestBy("caption")
                    .to(reference())
                    .where("kind").is(IMAGE)
                    .and("published").isTrue()
                    .limit(10)
                    .results();

            assertEquals(List.of("image nearest", "image third"), captions(hits));
        }

        @Test
        @DisplayName("or() starts a new group")
        void orStartsANewGroup() {
            List<TestAsset> hits = assets.nearestBy("caption")
                    .to(reference())
                    .where("kind").is(SHORT)
                    .or("rating").greaterThan(4)
                    .limit(10)
                    .results();

            assertEquals(List.of("audio nearest", "image nearest", "short nearest", "short second"),
                    captions(hits));
        }

        @Test
        @DisplayName("the comparison, range, nullness and string operators all narrow")
        void operatorVocabulary() {
            assertEquals(List.of("audio nearest", "audio second"), captions(assets.nearestBy("caption")
                    .to(reference()).where("rating").greaterThanOrEqual(4).and("kind").is(AUDIO)
                    .limit(10).results()));
            assertEquals(List.of("audio fifth", "short second"), captions(assets.nearestBy("caption")
                    .to(reference()).where("rating").lessThan(2).limit(10).results()));
            assertEquals(List.of("audio third", "audio fifth", "image second", "short second"),
                    captions(assets.nearestBy("caption")
                            .to(reference()).where("owner").isNull().limit(10).results()));
            assertEquals(List.of("audio fourth", "short nearest"), captions(assets.nearestBy("caption")
                    .to(reference()).where("owner").startingWith("car").limit(10).results()));
            assertEquals(List.of("audio second", "image third"), captions(assets.nearestBy("caption")
                    .to(reference()).where("owner").is("ben").limit(10).results()));
            assertEquals(List.of("audio nearest", "audio second", "audio fourth", "image nearest", "image third"),
                    captions(assets.nearestBy("caption")
                            .to(reference()).where("kind").isNot(SHORT).and("owner").isNotNull()
                            .and("rating").greaterThan(1).limit(10).results()));
        }

        @Test
        @DisplayName("offset skips matches")
        void offsetSkipsMatches() {
            List<TestAsset> hits = assets.nearestBy("caption")
                    .to(reference())
                    .where("kind").is(IMAGE)
                    .offset(1)
                    .limit(2)
                    .results();

            assertEquals(List.of("image second", "image third"), captions(hits));
        }

        @Test
        @DisplayName("ranked() carries the same similarities results() throws away")
        void rankedCarriesSimilarity() {
            List<Ranked<TestAsset>> hits = assets.nearestBy("caption")
                    .to(reference())
                    .where("kind").is(IMAGE)
                    .limit(1)
                    .ranked();

            assertEquals("image nearest", hits.get(0).entity().getCaption());
            assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(20), hits.get(0).similarity(), 1e-4);
        }

        @Test
        @DisplayName("the combined and summary vectors are reachable as well as a named field")
        void everyVectorKindIsReachable() {
            assertEquals(3, assets.nearest().to(reference()).limit(3).results().size());
            assertEquals(3, assets.nearestBySummary().to(reference()).limit(3).results().size());
        }

        // ---- misuse -----------------------------------------------------------------------------------

        @Test
        @DisplayName("an unknown property is refused on the call that named it, naming the entity")
        void unknownPropertyIsRefused() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> assets.nearestBy("caption").to(reference()).where("nonesuch"));

            assertTrue(thrown.getMessage().contains("nonesuch")
                    && thrown.getMessage().contains(TestAsset.class.getName()), thrown.getMessage());
        }

        @Test
        @DisplayName("an unknown @Vectorize field is refused, listing the ones that exist")
        void unknownVectorizeFieldIsRefused() {
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> assets.nearestBy("headline"));

            assertTrue(thrown.getMessage().contains("headline") && thrown.getMessage().contains("caption"),
                    thrown.getMessage());
        }

        @Test
        @DisplayName("a missing reference or limit is refused rather than guessed")
        void incompleteQueriesAreRefused() {
            assertThrows(IllegalStateException.class,
                    () -> assets.nearestBy("caption").limit(3).results());
            assertThrows(IllegalStateException.class,
                    () -> assets.nearestBy("caption").to(reference()).results());
        }

        @Test
        @DisplayName("a spent builder refuses to run twice rather than silently sharing predicate state")
        void aSpentBuilderIsRefused() {
            NearestQuery<TestAsset> query = assets.nearestBy("caption").to(reference()).limit(1);
            query.results();

            assertThrows(IllegalStateException.class, query::results);
        }

        @Test
        @DisplayName("a non-positive limit and a negative offset are refused")
        void nonsensicalWindowsAreRefused() {
            assertThrows(IllegalArgumentException.class, () -> assets.nearestBy("caption").limit(0));
            assertThrows(IllegalArgumentException.class, () -> assets.nearestBy("caption").offset(-1));
        }
    }
}
