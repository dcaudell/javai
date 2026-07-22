package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real container, not hermetic: there's no meaningful way to fake whether pgvector's {@code <=>} operator
 * actually ranks correctly. Uses {@link FakeEmbeddingProvider} for the embeddings themselves (only
 * genuinely-different-text-produces-genuinely-different-vectors matters here, not real semantic quality --
 * that's already proven against real embeddings in {@code e2e-client-test}).
 */
@Testcontainers
class RepositoryBackendHibernatePostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestArticleRepository repository;
    private static TestAccountRepository accountRepository;
    private static TestAccountNestedRepository nestedAccountRepository;
    private static TestVenueRepository venueRepository;
    private static TestTeamRepository teamRepository;
    private static TestCrewRepository crewRepository;

    @BeforeAll
    static void configurePersistenceAndProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        repository = JavAIPI.repository(TestArticleRepository.class, config);
        // Registered before first use, like every repository this SessionFactory must cover (TestProfile is
        // auto-registered via TestAccount's @OneToOne, so it needs no explicit repository call here).
        accountRepository = JavAIPI.repository(TestAccountRepository.class, config);
        nestedAccountRepository = JavAIPI.repository(TestAccountNestedRepository.class, config);
        // TestReview is auto-registered via TestVenue's reviews collection element type.
        venueRepository = JavAIPI.repository(TestVenueRepository.class, config);
        // TestMember is auto-registered via TestTeam's plain @OneToMany element type.
        teamRepository = JavAIPI.repository(TestTeamRepository.class, config);
        crewRepository = JavAIPI.repository(TestCrewRepository.class, config);
    }

    @BeforeEach
    void resetProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @AfterEach
    void resetConsistencyMode() {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    @Test
    void saveAndFindByIdRoundTrips() {
        TestArticle article = new TestArticle("Zero-day disclosed", "A critical vulnerability was patched today.");
        TestArticle saved = repository.save(article);
        assertTrue(saved.getId() != null);

        Optional<TestArticle> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Zero-day disclosed", found.get().getTitle());
        assertEquals("A critical vulnerability was patched today.", found.get().getBody());
    }

    @Test
    void findNearestByFieldVectorRanksBySimilarity() {
        TestArticle security = repository.save(new TestArticle("Zero-day disclosed",
                "A critical vulnerability affecting a widely used TLS library was patched today."));
        TestArticle cooking = repository.save(new TestArticle("Weeknight pasta recipes",
                "Quick, easy pasta dishes you can make in under thirty minutes."));
        TestArticle sports = repository.save(new TestArticle("Championship game recap",
                "The local team secured a dramatic overtime victory last night."));

        EmbeddingVector reference = security.fieldVector("title");
        List<TestArticle> nearest = repository.findNearestByTitleVector(reference, 1);

        assertEquals(1, nearest.size());
        assertEquals(security.getId(), nearest.get(0).getId(),
                "the article whose own title produced the reference vector must rank nearest to itself");
    }

    @Test
    void findNearestByVectorAndSummaryVectorAlsoWork() {
        TestArticle article = repository.save(new TestArticle("Combined and summary vector test",
                "Proves the whole-object vector() and summaryVector() query variants, not just per-field."));

        List<TestArticle> byVector = repository.findNearestByVector(article.vector(), 5);
        assertTrue(byVector.stream().anyMatch(a -> a.getId().equals(article.getId())));

        List<TestArticle> bySummary = repository.findNearestBySummaryVector(article.summaryVector(), 5);
        assertTrue(bySummary.stream().anyMatch(a -> a.getId().equals(article.getId())));
    }

    /**
     * Structural proof, not just behavioral: two different models' vectors for the *same* field of the
     * *same* entity land in two entirely separate tables (named from {@link ModelIds#sanitize}), each
     * holding exactly that model's own row -- never a shared table filtered by a {@code model_id} column.
     */
    @Test
    void savingUnderADifferentModelUsesASeparateTablePerModel() throws Exception {
        TestArticle article = repository.save(new TestArticle("Model migration test", "Original text."));
        UUID id = article.getId();
        String originalModelId = article.fieldVector("title").modelId();

        FakeEmbeddingProvider baseProvider = new FakeEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = baseProvider.embed(text);
            return new EmbeddingVector(base.values(), "fake-test-model-v2", base.dims(), Instant.now());
        });
        TestArticle reloaded = repository.findById(id).orElseThrow();
        repository.save(reloaded);

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, countRows(connection, "javai_vectors__" + ModelIds.sanitize(originalModelId), id, "title"),
                    "the original model's table must still hold exactly this entity's row, untouched");
            assertEquals(1, countRows(connection, "javai_vectors__" + ModelIds.sanitize("fake-test-model-v2"), id, "title"),
                    "the new model must have its own, separate table");
        }
    }

    private static int countRows(Connection connection, String table, UUID id, String fieldName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE owner_id = ? AND field_name = ?")) {
            statement.setObject(1, id);
            statement.setString(2, fieldName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * A different-dimension model swap is now *exactly* the same operation as a same-dimension one: just
     * {@code configureEmbeddingProvider(...)}, nothing else. Each model gets its own, independently and
     * correctly dimensioned table the first time it's needed, so there's no shared-column conflict to hit
     * -- contrast with the earlier, since-replaced shared-table design, where this used to fail outright
     * with a pgvector dimension-mismatch error.
     */
    @Test
    void differentDimensionModelSwapWorksTheSameAsASameDimensionSwap() {
        TestArticle article = repository.save(new TestArticle("Dimension swap test", "Original text."));
        UUID id = article.getId();

        JavAIRuntime.configureEmbeddingProvider(text -> new EmbeddingVector(
                new float[] {0.1f, 0.2f, 0.3f, 0.4f}, "fake-test-model-4dim", 4, Instant.now()));
        TestArticle reloaded = repository.findById(id).orElseThrow();
        TestArticle resaved = repository.save(reloaded); // must NOT throw, unlike the old shared-table design

        List<TestArticle> found = repository.findNearestByTitleVector(resaved.fieldVector("title"), 5);
        assertTrue(found.stream().anyMatch(a -> a.getId().equals(id)));
    }

    @Test
    void reindexAllReembedsExistingEntitiesUnderTheNewModel() {
        TestArticle article = repository.save(new TestArticle("Reindex test", "Some original text about topic A."));
        UUID id = article.getId();

        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = new FakeEmbeddingProvider().embed(text);
            return new EmbeddingVector(base.values(), "fake-test-model-reindexed", base.dims(), Instant.now());
        });
        repository.reindexAll();

        TestArticle reloaded = repository.findById(id).orElseThrow();
        List<TestArticle> found = repository.findNearestByTitleVector(reloaded.fieldVector("title"), 5);
        assertTrue(found.stream().anyMatch(a -> a.getId().equals(id)),
                "reindexAll() must re-embed and persist every existing entity under the newly configured model");
    }

    @Test
    void revertingToAPreviousProviderNeedsNoReindexing() {
        TestArticle article = repository.save(new TestArticle("Revert test", "Some original text about topic B."));
        UUID id = article.getId();
        EmbeddingVector originalReference = article.fieldVector("title");

        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = new FakeEmbeddingProvider().embed(text);
            return new EmbeddingVector(base.values(), "fake-test-model-temporary", base.dims(), Instant.now());
        });
        repository.reindexAll();

        // Revert -- deliberately no reindexAll() call here.
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());

        List<TestArticle> found = repository.findNearestByTitleVector(originalReference, 5);
        assertTrue(found.stream().anyMatch(a -> a.getId().equals(id)),
                "the original model's table must still hold this entity's vector -- reverting must not need reindexing");
    }

    @Test
    void bogusDerivedQueryMethodFailsFastAtRepositoryCreation() {
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(BogusTestArticleRepository.class, config));
    }

    // ---- ordinary (non-vector) derived finders, OMI-138 ----------------------------------------

    @Test
    void ordinaryDerivedFindersWorkAcrossOperatorsProjectionsAndPaging() {
        DerivedFinderTestSupport.seed(accountRepository);
        DerivedFinderTestSupport.assertSimpleDerivedFinders(accountRepository);
    }

    @Test
    void nestedAssociationDerivedFindersTraverseTheSingularOneToOne() {
        DerivedFinderTestSupport.seed(accountRepository);
        DerivedFinderTestSupport.assertNestedDerivedFinders(nestedAccountRepository);
    }

    @Test
    void unknownPropertyDerivedFinderFailsFastAtRepositoryCreation() {
        assertThrows(IllegalArgumentException.class, () -> JavAIPI.repository(TestBadPropertyRepository.class, config));
    }

    @Test
    void nestedToManyEmptinessRegexAndGeoFindersWork() {
        DerivedFinderTestSupport.seedVenues(venueRepository);
        DerivedFinderTestSupport.assertNestedToManyEmptinessRegexAndGeoFinders(venueRepository);
    }

    // ---- plain (Hibernate-owned) collections vs. JavAI collections, OMI-142 --------------------

    /**
     * The regression this fix exists for. A plain {@code @OneToMany} used to be claimed by BOTH Hibernate's
     * own mapping and {@code javai_collection_members}, so hydration added every member a second time on top
     * of the already-Hibernate-populated collection -- 2 members saved, 4 read back, silently. Now the side
     * table only claims JavAI collection fields, so Hibernate owns this association end to end.
     */
    @Test
    void plainOneToManyRoundTripsWithoutDuplicatingMembers() {
        TestTeam team = new TestTeam("platform");
        team.getMembers().add(new TestMember("ada"));
        team.getMembers().add(new TestMember("grace"));
        TestTeam saved = teamRepository.save(team);

        TestTeam reloaded = teamRepository.findById(saved.getId()).orElseThrow();
        assertEquals(2, reloaded.getMembers().size(),
                "a natively-mapped @OneToMany must round-trip exactly its own members, not doubled ones");
        assertEquals(List.of("ada", "grace"),
                reloaded.getMembers().stream().map(TestMember::getNickname).sorted().toList());
    }

    /** The vector bridge: Hibernate's cascade INSERTs the members, but JavAI must still force and persist
     *  their vectors even though this collection never touches {@code javai_collection_members}. */
    @Test
    void plainOneToManyMembersStillGetTheirVectorsPersisted() throws Exception {
        TestTeam team = new TestTeam("vector-bridge");
        team.getMembers().add(new TestMember("turing"));
        TestTeam saved = teamRepository.save(team);

        TestMember member = saved.getMembers().get(0);
        assertTrue(member.getId() != null, "the cascaded member must have been assigned an id");
        String table = "javai_vectors__" + ModelIds.sanitize(member.fieldVector("nickname").modelId());
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, countRows(connection, table, member.getId(), "nickname"),
                    "a @JavAIVectorizable member of a Hibernate-owned collection must still get a vector row");
        }
    }

    /** Symmetric cleanup: the member has no membership row to drive deletion from, so the cascade-aware
     *  path must clear its vector rows before Hibernate removes it. */
    @Test
    void deletingOwnerClearsVectorRowsOfCascadeRemovedMembers() throws Exception {
        TestTeam team = new TestTeam("cleanup");
        team.getMembers().add(new TestMember("hopper"));
        TestTeam saved = teamRepository.save(team);
        TestMember member = saved.getMembers().get(0);
        String table = "javai_vectors__" + ModelIds.sanitize(member.fieldVector("nickname").modelId());

        teamRepository.deleteById(saved.getId());

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(0, countRows(connection, table, member.getId(), "nickname"),
                    "a cascade-removed member must not leave an orphaned vector row behind");
        }
    }

    /**
     * A JPA association annotation on a JavAI collection field is rejected rather than silently ignored --
     * it would otherwise give JavAI's side-table storage and its hardcoded owner-owns-members cascade instead
     * of the requested JPA semantics (actively unsafe for {@code @ManyToMany}). Uses its own config so it gets
     * a fresh backend whose SessionFactory hasn't been built yet, the same pattern as the KnowledgeGraph test.
     */
    @Test
    void javaiCollectionAnnotatedWithOneToManyFailsFastAtRegistration() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestAnnotatedJavAICollectionRepository.class, freshConfig()));
        assertTrue(thrown.getMessage().contains("@OneToMany"));
        assertTrue(thrown.getMessage().contains("JavAIList"), "message must point at the interface-typed fix");
    }

    /** A plain collection with no mapping annotation can't be mapped by Hibernate at all -- rejected here
     *  with a clearer message than the boot-time failure it would otherwise cause. */
    @Test
    void plainCollectionWithoutMappingAnnotationFailsFastAtRegistration() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestUnmappedPlainCollectionRepository.class, freshConfig()));
        assertTrue(thrown.getMessage().contains("@ElementCollection"));
    }

    // ---- Phase 1: listener-driven vector coverage --------------------------------------------

    /**
     * The gap Phase 1 closes. {@code TestProfile} here sits TWO cascade hops from the saved root
     * (team -> members[] -> profile), so the one-level reflective walk never reaches it -- Hibernate would
     * INSERT it while its vector row silently never appeared. The flush listener reports every entity
     * Hibernate actually persisted, at any depth, so its vectors get written too.
     */
    @Test
    void deeplyCascadedEntityGetsItsVectorsWritten() throws Exception {
        TestTeam team = new TestTeam("deep-cascade");
        TestMember member = new TestMember("deep-member");
        member.setProfile(new TestProfile("deep-handle", "Portland"));
        team.getMembers().add(member);

        TestTeam saved = teamRepository.save(team);
        TestProfile profile = saved.getMembers().get(0).getProfile();
        assertTrue(profile.getId() != null, "the two-hop entity must have been assigned an id");

        String table = "javai_vectors__" + ModelIds.sanitize(profile.fieldVector("handle").modelId());
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, countRows(connection, table, profile.getId(), "handle"),
                    "an entity Hibernate cascaded two hops deep must still get its vector row");
        }
    }

    /**
     * The Gate 3 acceptance criterion. {@code reindexAll()} re-saves entities whose mapped columns are
     * unchanged, so Hibernate skips the UPDATE and fires NO event at all -- proven in
     * {@code PhaseZeroSpikeTest}. The explicit vector-write path must therefore survive alongside the
     * listener, or re-embedding under a newly configured model would silently do nothing.
     */
    @Test
    void reindexAllStillReembedsWhenNoHibernateUpdateEventFires() throws Exception {
        TestTeam team = new TestTeam("reindex-listener-era");
        team.getMembers().add(new TestMember("reindex-member"));
        TestTeam saved = teamRepository.save(team);
        UUID memberId = saved.getMembers().get(0).getId();

        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = new FakeEmbeddingProvider().embed(text);
            return new EmbeddingVector(base.values(), "fake-model-phase1-reindex", base.dims(), Instant.now());
        });
        teamRepository.reindexAll();

        String table = "javai_vectors__" + ModelIds.sanitize("fake-model-phase1-reindex");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, countRows(connection, table, memberId, "nickname"),
                    "reindexAll() must re-embed under the new model even though no Hibernate event fires");
        }
    }

    // ---- Phase 2: JavAI collections as native Hibernate associations --------------------------

    /**
     * The Phase 2 payoff. {@link TestCrew} declares only a plain JPA {@code @OneToMany} over a
     * JavAI-interface-typed field -- no {@code @CollectionType}, nothing JavAI-specific. The backend attaches
     * the collection type during mapping, so Hibernate owns the association (its own join table, cascade,
     * lazy loading) while the instance it substitutes into the field is a real {@link PersistentJavAIList}
     * with working vector behavior. This is "JPA works like it always did, and the vectors just happen."
     */
    @Test
    void javaiInterfaceTypedCollectionBecomesANativeHibernateAssociation() throws Exception {
        TestCrew crew = new TestCrew("phase2-crew");
        crew.getMembers().add(new TestMember("neil"));
        crew.getMembers().add(new TestMember("buzz"));
        TestCrew saved = crewRepository.save(crew);

        TestCrew reloaded = crewRepository.findById(saved.getId()).orElseThrow();
        JavAIList<TestMember> members = reloaded.getMembers();

        // Hibernate substituted JavAI's own persistent collection -- not a plain PersistentBag...
        assertInstanceOf(PersistentJavAIList.class, members,
                "the backend must attach the JavAI collection type without the consumer annotating anything");
        // ...it round-trips exactly once (no side-table double-claim)...
        assertEquals(2, members.size());
        assertEquals(List.of("buzz", "neil"),
                members.stream().map(TestMember::getNickname).sorted().toList());
        // ...and the JavAI behavior works on the Hibernate-managed instance.
        assertTrue(members.centroid().dims() > 0, "centroid() must work on the Hibernate-hydrated collection");

        // Members are @JavAIVectorizable, so their vectors must be persisted too.
        TestMember member = members.get(0);
        String table = "javai_vectors__" + ModelIds.sanitize(member.fieldVector("nickname").modelId());
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, countRows(connection, table, member.getId(), "nickname"),
                    "members of a natively-mapped JavAI collection must still get vector rows");
        }
    }

    /** Hibernate owns the association, so it must be a real join table -- not this backend's
     *  {@code javai_collection_members} side table. */
    @Test
    void nativelyMappedJavAICollectionUsesHibernatesJoinTableNotTheSideTable() throws Exception {
        TestCrew crew = new TestCrew("phase2-storage");
        crew.getMembers().add(new TestMember("sally"));
        TestCrew saved = crewRepository.save(crew);

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM javai_collection_members WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, TestCrew.class.getName());
                statement.setObject(2, saved.getId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    assertEquals(0, resultSet.getInt(1),
                            "a natively-mapped JavAI collection must not be claimed by the side table");
                }
            }
            // Hibernate's own join table for the association exists and holds the row.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                            + "AND table_name = 'testcrew_testmember'");
                    ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(1, resultSet.getInt(1), "Hibernate must have created its own join table");
            }
        }
    }

    /**
     * {@code reindexAll()} does what its name says: re-embeds the whole datastore, not just the type whose
     * repository it was called through. Re-indexing only one type would leave the store straddling two
     * embedding models -- an {@code Article} on the new model while its {@code Comment}s are still on the old
     * one -- which is exactly the state a re-index exists to avoid.
     */
    @Test
    void reindexAllReembedsEveryRegisteredTypeNotJustItsOwn() throws Exception {
        TestTeam team = new TestTeam("cross-type-reindex");
        team.getMembers().add(new TestMember("cross-type-member"));
        UUID memberId = teamRepository.save(team).getMembers().get(0).getId();
        TestArticle article = repository.save(new TestArticle("cross-type", "article body"));

        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = new FakeEmbeddingProvider().embed(text);
            return new EmbeddingVector(base.values(), "fake-model-crosstype", base.dims(), Instant.now());
        });
        // Called through the ARTICLE repository -- yet the member (a different type) must be re-embedded too.
        repository.reindexAll();

        String table = "javai_vectors__" + ModelIds.sanitize("fake-model-crosstype");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, countRows(connection, table, article.getId(), "title"),
                    "the repository's own type must be re-embedded");
            assertEquals(1, countRows(connection, table, memberId, "nickname"),
                    "a DIFFERENT registered type must be re-embedded too -- reindexAll means all");
        }
    }

    // ---- Phase 3: nested finders over natively-mapped collections use a Criteria JOIN ---------

    /**
     * A nested filter through a to-many association Hibernate owns is now a single Criteria JOIN rather than
     * the id-set-per-hop resolution the side table requires. Exercised on both natively-mapped shapes: an
     * interface-typed JavAI collection ({@link TestCrew}) and a plain JDK {@code @OneToMany}
     * ({@link TestTeam}). The join must not multiply rows -- an owner with two matching members appears once.
     */
    @Test
    void nestedFinderOverNativelyMappedCollectionUsesAJoinAndDoesNotDuplicateRows() {
        TestCrew crew = new TestCrew("phase3-crew");
        crew.getMembers().add(new TestMember("phase3-alpha"));
        crew.getMembers().add(new TestMember("phase3-beta"));
        crewRepository.save(crew);

        TestTeam team = new TestTeam("phase3-team");
        team.getMembers().add(new TestMember("phase3-gamma"));
        teamRepository.save(team);

        assertEquals(List.of("phase3-crew"),
                crewRepository.findByMembersNickname("phase3-alpha").stream().map(TestCrew::getName).toList());
        assertEquals(List.of("phase3-team"),
                teamRepository.findByMembersNickname("phase3-gamma").stream().map(TestTeam::getName).toList());
        assertTrue(crewRepository.findByMembersNickname("no-such-member").isEmpty());
        assertEquals(1, crewRepository.countByMembersNickname("phase3-beta"));
    }

    /** Emptiness on a natively-mapped collection resolves through Criteria's own {@code isEmpty}/
     *  {@code isNotEmpty} -- no side table consulted. */
    @Test
    void emptinessOverNativelyMappedCollectionUsesCriteriaDirectly() {
        TestCrew withMembers = new TestCrew("phase3-populated");
        withMembers.getMembers().add(new TestMember("phase3-delta"));
        TestCrew savedPopulated = crewRepository.save(withMembers);
        TestCrew savedEmpty = crewRepository.save(new TestCrew("phase3-empty"));

        List<UUID> emptyIds = crewRepository.findByMembersIsEmpty().stream().map(TestCrew::getId).toList();
        List<UUID> nonEmptyIds = crewRepository.findByMembersIsNotEmpty().stream().map(TestCrew::getId).toList();

        assertTrue(emptyIds.contains(savedEmpty.getId()), "the member-less crew must be reported empty");
        assertFalse(emptyIds.contains(savedPopulated.getId()));
        assertTrue(nonEmptyIds.contains(savedPopulated.getId()));
        assertFalse(nonEmptyIds.contains(savedEmpty.getId()));
    }

    /**
     * The counterpart to {@code reindexAll()}: {@code reindex()} is deliberately narrow, re-embedding only
     * its own repository's type and leaving others on whatever model they were last written under. Asserting
     * the negative here is the point -- it's what distinguishes the two methods, and what makes the unused
     * {@code Class} parameter's removal from the SPI safe.
     */
    @Test
    void reindexReembedsOnlyItsOwnTypeUnlikeReindexAll() throws Exception {
        TestTeam team = new TestTeam("narrow-reindex");
        team.getMembers().add(new TestMember("narrow-member"));
        UUID memberId = teamRepository.save(team).getMembers().get(0).getId();
        TestArticle article = repository.save(new TestArticle("narrow-reindex", "article body"));

        JavAIRuntime.configureEmbeddingProvider(text -> {
            EmbeddingVector base = new FakeEmbeddingProvider().embed(text);
            return new EmbeddingVector(base.values(), "fake-model-narrow", base.dims(), Instant.now());
        });
        repository.reindex(); // articles only -- NOT the whole datastore

        String table = "javai_vectors__" + ModelIds.sanitize("fake-model-narrow");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            assertEquals(1, countRows(connection, table, article.getId(), "title"),
                    "reindex() must re-embed its own type");
            assertEquals(0, countRows(connection, table, memberId, "nickname"),
                    "reindex() must NOT touch other types -- that is what reindexAll() is for");
        }
    }

    private static JavAIPersistenceConfig freshConfig() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
    }

    /**
     * {@code KnowledgeGraph} persistence is Neo4j-only (see {@link RepositoryBackendNeo4jTest}'s own
     * KnowledgeGraph round-trip tests) -- proves the Postgres backend rejects a {@code KnowledgeGraph}-typed
     * field clearly, at registration time, rather than failing confusingly deep inside Hibernate's boot-time
     * mapping once an unmappable field type is discovered.
     */
    @Test
    void knowledgeGraphFieldFailsFastAtRegistrationInsteadOfConfusingHibernateMappingFailure() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestOwnerWithGraphRepository.class,
                        JavAIPersistenceConfig.builder()
                                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                                .postgresUrl(postgres.getJdbcUrl())
                                .postgresUsername(postgres.getUsername())
                                .postgresPassword(postgres.getPassword())
                                .build()));
        assertTrue(thrown.getMessage().contains("KnowledgeGraph"));
        assertTrue(thrown.getMessage().contains("Neo4j"));
    }

    /**
     * The persistence must-have that doesn't depend on which {@link EmbeddingConsistencyMode} is active:
     * a save() immediately following a mutation, with no intervening explicit read, must still persist the
     * vector matching the field's *final* value -- IMMEDIATE_CONSISTENCY never eagerly computes on mutation
     * (only a subsequent read does), so without {@code writeVectors()}'s own forced-blocking read via
     * {@code fieldVector()}, this would either persist a stale value or fail outright.
     */
    @Test
    void savedVectorIsAlwaysAccurateUnderImmediateConsistency() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        TestArticle article = repository.save(new TestArticle("first title", "first body"));
        UUID id = article.getId();

        article.setTitle("second title, mutated just before save");
        TestArticle resaved = repository.save(article);

        EmbeddingVector expected = resaved.fieldVector("title");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            float[] stored = readVectorLiteral(connection,
                    "javai_vectors__" + ModelIds.sanitize(expected.modelId()), id, "title");
            assertArrayEquals(expected.values(), stored, 1e-4f,
                    "the persisted vector must match the field's value as of save(), not some earlier value");
        }
    }

    /**
     * The race this whole locking mechanism (JavAIRuntime.runWithSubgraphLockedForPersistence) exists to
     * prevent: under EVENTUAL_CONSISTENCY with a deliberately slow provider, a mutation's own eager
     * background dispatch has *not* landed by the time save() is called immediately afterward -- if
     * writeVectors() merely read whatever {@code fieldVector()} would serve under the ambient (non-forced)
     * mode, it would persist the OLD, now-stale vector. Forcing accuracy for the duration of the flush is
     * what makes the persisted value correct regardless.
     */
    @Test
    void savedVectorIsAlwaysAccurateUnderEventualConsistencyDespiteASlowBackgroundProvider() throws Exception {
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.EVENTUAL_CONSISTENCY);
        TestArticle article = repository.save(new TestArticle("first title", "first body"));
        UUID id = article.getId();

        FakeEmbeddingProvider fast = new FakeEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(text -> {
            try {
                Thread.sleep(300); // slower than this test's own save() call would otherwise wait for
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fast.embed(text);
        });

        article.setTitle("second title, mutated just before a slow-provider save");
        TestArticle resaved = repository.save(article); // must block on the slow provider, not skip ahead

        EmbeddingVector expected = resaved.fieldVector("title");
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            float[] stored = readVectorLiteral(connection,
                    "javai_vectors__" + ModelIds.sanitize(expected.modelId()), id, "title");
            assertArrayEquals(expected.values(), stored, 1e-4f,
                    "EVENTUAL_CONSISTENCY's slow background dispatch must never be allowed to leave a stale "
                            + "vector in the database -- the flush itself must have forced an accurate, "
                            + "blocking recompute");
        }
    }

    private static float[] readVectorLiteral(Connection connection, String table, UUID id, String fieldName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT vector::text FROM " + table + " WHERE owner_id = ? AND field_name = ?")) {
            statement.setObject(1, id);
            statement.setString(2, fieldName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "expected a row in " + table + " for " + id + "/" + fieldName);
                String literal = resultSet.getString(1); // pgvector's text form, e.g. "[0.1,0.2,0.3]"
                String[] parts = literal.substring(1, literal.length() - 1).split(",");
                float[] values = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    values[i] = Float.parseFloat(parts[i]);
                }
                return values;
            }
        }
    }
}
