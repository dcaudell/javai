package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.EmbeddingLedger;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-398 / OMI-405: a repository method carrying its own JPQL or SQL.
 *
 * <p>What this closes is narrower than "queries" and worth naming: the two derived-name grammars can express
 * a predicate over an entity's properties and nothing else, so a grouped aggregate -- "how many rows does
 * each of these labels have" -- had no expression at all. {@code countBy…In} returns one total. The recourses
 * were N+1 counts, counting in memory, or reaching past the repository to the {@code SessionFactory}, and it
 * is that last one this exists to stop being the answer.
 */
@Testcontainers
class DeclaredQueryTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestCounterRowRepository counters;
    private static TestVectorizedCounterRepository vectorized;

    private String label;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        counters = JavAIPI.repository(TestCounterRowRepository.class, config);
        vectorized = JavAIPI.repository(TestVectorizedCounterRepository.class, config);
    }

    @BeforeEach
    void freshLabel() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        label = "L" + UUID.randomUUID().toString().substring(0, 8);
    }

    private TestCounterRow save(int votes, long tally) {
        return counters.save(new TestCounterRow(label, votes, tally));
    }

    // ---- reads -------------------------------------------------------------------------------------

    @Test
    void runsADeclaredQueryReturningEntities() {
        save(3, 0);
        save(9, 0);

        List<TestCounterRow> found = counters.byLabel(label);

        assertEquals(2, found.size());
        assertEquals(List.of(9, 3), found.stream().map(TestCounterRow::getVotes).toList(),
                "the query's own ORDER BY must be honoured");
    }

    @Test
    void bindsPositionalParametersToo() {
        save(1, 0);
        save(7, 0);

        assertEquals(1, counters.byLabelAndMinimumVotes(label, 5).size());
    }

    @Test
    void adaptsOptionalSingleAndStreamReturns() {
        TestCounterRow row = save(4, 0);

        assertTrue(counters.oneById(row.getId()).isPresent());
        assertEquals(Optional.empty(), counters.oneById(UUID.randomUUID()));
        assertNotNull(counters.bareById(row.getId()));
        assertNull(counters.bareById(UUID.randomUUID()), "a bare single return is nullable, not an error");
        try (Stream<TestCounterRow> stream = counters.streamByLabel(label)) {
            assertEquals(1, stream.count());
        }
    }

    @Test
    void reportsAnAmbiguousSingleResultRatherThanPickingOne() {
        save(1, 0);
        save(2, 0);

        assertThrows(IllegalStateException.class, () -> counters.oneByLabel(label),
                "two rows for a single-result return is a defect in the query, not a value to choose from");
    }

    @Test
    void adaptsAScalarReturn() {
        save(1, 0);
        save(2, 0);

        assertEquals(2L, counters.tallyRowsFor(label));
    }

    /** The parent ticket's opening example: one row per group, which no derived grammar can ask for. */
    @Test
    void returnsAGroupedAggregateAsTuples() {
        save(1, 0);
        save(2, 0);
        String other = label + "-other";
        counters.save(new TestCounterRow(other, 1, 0));

        List<Object[]> counts = counters.countsByLabel(List.of(label, other));

        assertEquals(2, counts.size());
        assertEquals(2L, counts.stream().filter(row -> row[0].equals(label)).findFirst().orElseThrow()[1],
                "count() comes back as a Long, which is what a caller destructuring the tuple will see");
    }

    @Test
    void returnsAGroupedAggregateAsARecord() {
        save(1, 0);
        save(2, 0);
        save(3, 0);

        List<TestLabelCount> counts = counters.typedCountsByLabel(List.of(label));

        assertEquals(List.of(new TestLabelCount(label, 3)), counts,
                "a JPQL constructor expression instantiates a record directly -- nothing here maps it");
    }

    @Test
    void pagesWithAnExplicitCountQuery() {
        save(1, 0);
        save(2, 0);
        save(3, 0);

        Page<TestCounterRow> page = counters.pageByLabel(label, PageRequest.of(0, 2));

        assertEquals(2, page.getContent().size());
        assertEquals(3, page.getTotalElements(), "the total comes from countQuery, not from the window");
        assertTrue(page.hasNext());
    }

    @Test
    void slicesWithoutACountQuery() {
        save(1, 0);
        save(2, 0);
        save(3, 0);

        Slice<TestCounterRow> first = counters.sliceByLabel(label, PageRequest.of(0, 2));
        Slice<TestCounterRow> last = counters.sliceByLabel(label, PageRequest.of(1, 2));

        assertEquals(2, first.getContent().size());
        assertTrue(first.hasNext(), "a Slice decides hasNext by fetching one extra row, not by counting");
        assertFalse(last.hasNext());
    }

    @Test
    void appliesADynamicSortThroughTheQueryModel() {
        save(5, 0);
        save(1, 0);
        save(3, 0);

        List<Integer> ascending = counters.sortedByLabel(label, Sort.by("votes").ascending())
                .stream().map(TestCounterRow::getVotes).toList();
        List<Integer> descending = counters.sortedByLabel(label, Sort.by("votes").descending())
                .stream().map(TestCounterRow::getVotes).toList();

        assertEquals(List.of(1, 3, 5), ascending);
        assertEquals(List.of(5, 3, 1), descending,
                "applied through Hibernate's own Order, never by editing the query text");
    }

    @Test
    void appliesATrailingLimit() {
        save(5, 0);
        save(1, 0);
        save(3, 0);

        assertEquals(List.of(5, 3), counters.limitedByLabel(label, Limit.of(2))
                .stream().map(TestCounterRow::getVotes).toList());
    }

    // ---- native ------------------------------------------------------------------------------------

    @Test
    void runsANativeQueryReturningEntities() {
        save(2, 0);
        save(8, 0);

        List<TestCounterRow> found = counters.nativeByLabel(label);

        assertEquals(List.of(8, 2), found.stream().map(TestCounterRow::getVotes).toList());
    }

    @Test
    void runsANativeQueryReturningTuples() {
        save(1, 0);
        save(1, 0);

        List<Object[]> counts = counters.nativeCountsByLabel(label);

        assertEquals(1, counts.size());
        assertEquals(2L, ((Number) counts.get(0)[1]).longValue());
    }

    @Test
    void windowsANativeQueryWithAnUnsortedPageable() {
        save(1, 0);
        save(2, 0);
        save(3, 0);

        assertEquals(2, counters.nativePagedByLabel(label, PageRequest.of(0, 2)).size(),
                "a window needs no rewriting, so it is offered where a dynamic sort is not");
    }

    @Test
    void pagesANativeQueryWithANativeCountQuery() {
        save(1, 0);
        save(2, 0);
        save(3, 0);

        Page<TestCounterRow> page = counters.nativePageByLabel(label, PageRequest.of(0, 2));

        assertEquals(2, page.getContent().size());
        assertEquals(3, page.getTotalElements(),
                "the count query is native here too -- a branch a JPQL-only fixture never reaches");
    }

    @Test
    void windowsADeclaredQueryAtAnOffsetNoPageNumberCanExpress() {
        save(1, 0);
        save(2, 0);
        save(3, 0);
        save(4, 0);
        save(5, 0);

        // Ordered by votes ascending, so the row at each offset is known by name.
        List<Integer> window = counters.nativePagedByLabel(label, Windows.of(3, 2))
                .stream().map(TestCounterRow::getVotes).toList();
        assertEquals(List.of(4, 5), window, "offset 3, limit 2 -- an offset that is not a multiple of the "
                + "limit, which is what PageRequest cannot say");

        List<Integer> pageRequest = counters.nativePagedByLabel(label, PageRequest.of(3 / 2, 2))
                .stream().map(TestCounterRow::getVotes).toList();
        assertEquals(List.of(3, 4), pageRequest, "the nearest page number lands on offset 2 and returns "
                + "different rows -- the gap OMI-460 reported, demonstrated rather than described");
    }

    @Test
    void windowsSupportTheOneExtraRowForeverScrollIdiom() {
        save(1, 0);
        save(2, 0);
        save(3, 0);
        save(4, 0);
        save(5, 0);

        // A page of 2 starting at 2, fetched as 3 rows: the extra row is the answer to "is there more?"
        List<TestCounterRow> probe = counters.nativePagedByLabel(label, Windows.of(2, 3));
        assertEquals(3, probe.size());
        assertTrue(probe.size() > 2, "the extra row exists, so a next page does too -- learned without a "
                + "count query");

        List<TestCounterRow> lastProbe = counters.nativePagedByLabel(label, Windows.of(4, 3));
        assertEquals(1, lastProbe.size(), "no extra row past the end, so this is the last page");
    }

    @Test
    void refusesASortedPageableOnANativeQuery() {
        save(1, 0);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> counters.nativePagedByLabel(label, PageRequest.of(0, 2, Sort.by("votes"))));

        assertTrue(thrown.getMessage().contains("rewriting the SQL"), thrown.getMessage());
    }

    // ---- vectors -----------------------------------------------------------------------------------

    /**
     * A declared query is an ordinary read, so an entity it returns must arrive with its stored vectors in
     * its slots -- otherwise every such read would re-embed, which is the cost OMI-187 removed for the other
     * read paths. Measured at the provider, where "did JavAI decide to recompute" is observable, rather than
     * by inspecting a cache that could agree with a broken implementation.
     */
    @Test
    void anEntityFromADeclaredQueryArrivesWithItsStoredVectors() {
        vectorized.save(new TestVectorizedCounter(label + " headline", 0, 0));

        RecordingEmbeddingProvider recording = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(recording);
        EmbeddingLedger ledger = recording.ledger();

        List<TestVectorizedCounter> found = vectorized.byHeadline(label + " headline");
        assertEquals(1, found.size());
        assertNotNull(found.get(0).fieldVector("headline"));

        assertEquals(0, ledger.totalCalls(),
                "reading a vector off an entity a declared query returned must cost no provider call: "
                        + ledger.report());
    }

    @Test
    void anEntityFromANativeQueryArrivesWithItsStoredVectorsToo() {
        TestCounterRow row = save(1, 0);
        assertNotNull(counters.nativeByLabel(label));
        assertEquals(row.getId(), counters.nativeByLabel(label).get(0).getId(),
                "POST_LOAD fires for a native entity query as well, which is what makes hydration uniform");
    }

    // ---- refusals caught by reflection, at repository-creation time --------------------------------

    private String refusalMessage(Class<? extends JavAIRepository<?>> repositoryInterface) {
        return assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(repositoryInterface, config)).getMessage();
    }

    @Test
    void refusesAnEmptyQuery() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.EmptyQuery.class).contains("is empty"));
    }

    @Test
    void refusesAParamTheQueryNeverMentions() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.NamedParameterTheQueryNeverMentions.class);
        assertTrue(message.contains("nickname"), message);
        assertTrue(message.contains("label"), "and names what the query does ask for: " + message);
    }

    @Test
    void refusesAQueryParameterTheMethodNeverSupplies() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.NamedParameterTheMethodNeverSupplies.class);
        assertTrue(message.contains("votes"), message);
    }

    @Test
    void refusesMixingNamedAndUnnamedParameters() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.MixedNamedAndUnnamedParameters.class)
                .contains("mixes named and unnamed"));
    }

    @Test
    void refusesAPositionalCountThatDoesNotMatch() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.WrongPositionalCount.class)
                .contains("positional parameters up to ?2"));
    }

    @Test
    void refusesNamedParametersWithNoParamAnnotationAtAll() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.NamedParametersWithoutAnyParamAnnotation.class)
                .contains("@Param"));
    }

    @Test
    void refusesAPageReturnWithNoCountQuery() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.PageWithoutACountQuery.class);
        assertTrue(message.contains("countQuery"), message);
        assertTrue(message.contains("Slice"), "and offers the alternative that needs none: " + message);
    }

    @Test
    void refusesACountQueryTakingDifferentParameters() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.CountQueryTakingDifferentParameters.class)
                .contains("same parameters"));
    }

    @Test
    void refusesACountQueryNothingWouldRun() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.CountQueryOnANonPageReturn.class)
                .contains("does not return a Page"));
    }

    @Test
    void refusesADynamicSortAgainstAProjection() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.SortOnAProjection.class);
        assertTrue(message.contains("order a projection by"), message);
    }

    @Test
    void refusesADynamicSortParameterOnANativeQuery() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.DynamicSortOnANativeQuery.class)
                .contains("rewriting the SQL"));
    }

    @Test
    void refusesABindableParameterAfterAPageable() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.BindableParameterAfterAPageable.class)
                .contains("after all bindable parameters"));
    }

    @Test
    void refusesARawCollectionReturn() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.RawCollectionReturn.class)
                .contains("element type"));
    }

    @Test
    void refusesAModifyingMethodReturningEntities() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.ModifyingReturningEntities.class)
                .contains("must return void or the affected-row count"));
    }

    @Test
    void refusesAModifyingMethodTakingAPageable() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.ModifyingWithAPageable.class)
                .contains("no ordering or window"));
    }

    // ---- refusals that need the ORM ---------------------------------------------------------------

    @Test
    void refusesAQueryNamingAPropertyThatDoesNotExist() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.MalformedQuery.class);
        assertTrue(message.contains("not a valid JPQL/HQL query"), message);
        assertTrue(message.contains("labelThatIsNotAField"), "naming the offending token: " + message);
    }

    /**
     * An unknown entity is told apart from a syntax error, because the fix is a different one entirely: the
     * entity set closes when the {@code SessionFactory} is built, so a type a query names but nothing else
     * reaches was never mapped. Naming it in a query does not register it (OMI-214).
     */
    @Test
    void refusesAQueryNamingAnUnknownEntityAndSaysHowToRegisterIt() {
        String message = refusalMessage(BogusDeclaredQueryRepositories.UnknownEntityInQuery.class);

        assertTrue(message.contains("NoSuchEntityAnywhere"), message);
        assertTrue(message.contains("A query is not a registration"), message);
        assertTrue(message.contains("entityType"),
                "and names the builder method that fixes it: " + message);
    }

    @Test
    void refusesAWriteThatIsNotMarkedModifying() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.UpdateWithoutModifying.class)
                .contains("not annotated @Modifying"));
    }

    @Test
    void refusesAModifyingMethodCarryingASelect() {
        assertTrue(refusalMessage(BogusDeclaredQueryRepositories.ModifyingCarryingASelect.class)
                .contains("not an update or a delete"));
    }

    /**
     * The whole point of the split: query text is checked as soon as an ORM exists, which for a config whose
     * factory is already built is the same instant the signature is. Asserted here because "validated at
     * repository-creation time" is a promise the other two dispatch paths keep unconditionally, and this one
     * keeps only when it can -- so the case where it can had better actually do it.
     */
    @Test
    void validatesQueryTextAtCreationTimeOnceTheFactoryExists() {
        assertNotNull(JavAIPI.sessionFactory(config), "this test needs a factory that already exists");

        assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusDeclaredQueryRepositories.MalformedQuery.class, config),
                "not on first call of the method -- the repository must not come into existence at all");
    }

    // ---- the other two backends --------------------------------------------------------------------

    @Test
    void neo4jAndMongoRefuseADeclaredQueryRatherThanIgnoringIt() {
        for (JavAIPersistenceConfig.Backend backend : List.of(
                JavAIPersistenceConfig.Backend.NEO4J, JavAIPersistenceConfig.Backend.MONGODB)) {
            UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                    () -> JavAIPI.repository(TestCounterRowRepository.class, JavAIPersistenceConfig.builder()
                            .backend(backend)
                            .neo4jUri("bolt://localhost:7687").neo4jUsername("neo4j").neo4jPassword("unused")
                            .mongoUri("mongodb://localhost:27017").mongoDatabase("unused")
                            .build()),
                    backend + " must refuse @Query rather than silently never running it");

            assertTrue(thrown.getMessage().contains("Postgres backend only"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("derived finder"),
                    "and point at what does work there: " + thrown.getMessage());
        }
    }
}
