package dev.xtrafe.javai.tagging;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.testsupport.ScriptedEmbeddingProvider;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filter-by-type on the two tag indexes, against all three real backends (OMI-460).
 *
 * <h2>The fixture makes the wrong implementation fail, not merely differ</h2>
 *
 * Every {@link TestTextedThing} is placed nearer the reference than every {@link TestTextedNote} -- things
 * at 2°/4°/6°, notes at 40°/45°/50°, through {@link ScriptedEmbeddingProvider}, whose cosine similarity to
 * the reference is exactly {@code cos θ}. The tag-summary vector of an instance carrying one tag is that
 * tag's own direction, so scripting the tag slugs places the instances.
 *
 * <p>So "the nearest 2 notes" and "the notes among the nearest 2" are not two readings of one question
 * here: the second is <b>empty</b>. That is the failure OMI-460 reported from the field -- a top-5 query
 * for albums coming back as three images and two albums -- reproduced where it can be asserted on.
 *
 * <h2>Why one class across three containers</h2>
 *
 * The behaviour must be identical; the mechanism is different in each store, and each has its own way to be
 * wrong. Postgres narrows with an ordinary {@code WHERE owner_type IN (…)} ahead of {@code ORDER BY}/
 * {@code LIMIT}. MongoDB hands the type list to {@code $vectorSearch}'s own {@code filter}, which works only
 * because the index declares {@code taggableType} as a filter field. Neo4j <em>cannot</em> pre-filter its
 * vector index at all, so a narrowed search there abandons the index for an exact
 * {@code vector.similarity.cosine} scan. Three mechanisms, one contract, one set of assertions
 * ({@link #assertNarrowing}) applied to each -- so a store that quietly stops honouring it is caught here
 * rather than in whichever suite happened to cover it.
 *
 * <p>The similarity assertions are the second half of the job: they are in closed form ({@code cos θ}),
 * which is what pins each backend's conversion of its own store's score into JavAI's common cosine
 * convention. Neo4j and Atlas both report {@code (1 + cos) / 2}, and a backend that forgets to undo that
 * still returns a perfectly plausible <em>ordering</em>.
 */
@Testcontainers
class NarrowedTagIndexAllBackendsTest {

    private static final String NEO4J_PASSWORD = "narrowed-tag-index-password";
    private static final String DATABASE = "javai_narrowed_tag_index";

    private static final double THING_NEAREST = 2;
    private static final double NOTE_NEAREST = 40;
    private static final double NOTE_SECOND = 45;
    private static final double NOTE_THIRD = 50;

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword(NEO4J_PASSWORD);

    /** See {@link JavAITaggingMongoE2ETest} for why {@code mongodb-atlas-local} and the healthcheck wait. */
    @Container
    static final GenericContainer<?> mongo =
            new GenericContainer<>(DockerImageName.parse("mongodb/mongodb-atlas-local:8.2"))
                    .withExposedPorts(27017)
                    .waitingFor(Wait.forHealthcheck())
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static ScriptedEmbeddingProvider provider;

    private static Fixture postgresFixture;
    private static Fixture neo4jFixture;
    private static Fixture mongoFixture;

    /** One store's seeded corpus: the tagging surface under test, plus the ids the assertions expect back. */
    private record Fixture(JavAITagRepository tagging, List<UUID> things, List<UUID> notes) {
    }

    @BeforeAll
    static void configure() {
        // Both strings per tag: the tag-summary vector embeds the slug, the tag-text vector embeds the "en"
        // display name. Scripting them to the same angle keeps one fixture true of both indexes.
        provider = new ScriptedEmbeddingProvider()
                .at("thing-near-one", THING_NEAREST).at("Thing Near One", THING_NEAREST)
                .at("thing-near-two", 4).at("Thing Near Two", 4)
                .at("thing-near-three", 6).at("Thing Near Three", 6)
                .at("note-far-one", NOTE_NEAREST).at("Note Far One", NOTE_NEAREST)
                .at("note-far-two", NOTE_SECOND).at("Note Far Two", NOTE_SECOND)
                .at("note-far-three", NOTE_THIRD).at("Note Far Three", NOTE_THIRD);
        JavAIRuntime.configureEmbeddingProvider(provider);

        postgresFixture = seed(JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build());
        neo4jFixture = seed(JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build());
        mongoFixture = seed(mongoConfig());
    }

    /** A <b>new</b> config object each call. {@code JavAIPersistenceConfig} does not override
     *  {@code equals}, and {@code JavAITagRepository} memoizes its backends in a map keyed by the config
     *  instance, so a fresh object is how a test gets a backend that has not already cached what it
     *  ensured -- which is exactly what {@link #mongoMigratesATagIndexThatPredatesNarrowing} needs. */
    private static JavAIPersistenceConfig mongoConfig() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri(mongoUri())
                .mongoDatabase(DATABASE)
                .build();
    }

    private static String mongoUri() {
        return "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?directConnection=true";
    }

    private static Fixture seed(JavAIPersistenceConfig config) {
        TagRepository tagRepository = JavAIPI.repository(TagRepository.class, config);
        TagSetRepository tagSets = JavAIPI.repository(TagSetRepository.class, config);
        TestTextedThingRepository things = JavAIPI.repository(TestTextedThingRepository.class, config);
        TestTextedNoteRepository notes = JavAIPI.repository(TestTextedNoteRepository.class, config);
        JavAITagRepository tagging = new JavAITagRepository(tagRepository, config);

        TagSet tagSet = tagSets.save(new TagSet("narrowing"));
        List<UUID> thingIds = new ArrayList<>();
        for (String display : List.of("Thing Near One", "Thing Near Two", "Thing Near Three")) {
            Tag tag = tagRepository.save(new Tag(tagSet, "en", display));
            TestTextedThing thing = things.save(new TestTextedThing(display));
            tagging.addTag(thing, tag);
            thingIds.add(thing.getId());
        }
        List<UUID> noteIds = new ArrayList<>();
        for (String display : List.of("Note Far One", "Note Far Two", "Note Far Three")) {
            Tag tag = tagRepository.save(new Tag(tagSet, "en", display));
            TestTextedNote note = notes.save(new TestTextedNote(display));
            tagging.addTag(note, tag);
            noteIds.add(note.getId());
        }
        return new Fixture(tagging, List.copyOf(thingIds), List.copyOf(noteIds));
    }

    @Test
    void postgresNarrowsBothTagIndexesByType() {
        assertNarrowing(postgresFixture);
    }

    @Test
    void neo4jNarrowsBothTagIndexesByType() {
        assertNarrowing(neo4jFixture);
    }

    @Test
    void mongoNarrowsBothTagIndexesByType() {
        assertNarrowing(mongoFixture);
    }

    /**
     * A MongoDB store whose tag-vector index was created <b>before</b> narrowing existed is migrated
     * automatically, rather than failing every narrowed search from then on.
     *
     * <h2>Why this needs a test rather than a note</h2>
     *
     * {@code $vectorSearch}'s {@code filter} may only touch paths the index declares as filter fields, and
     * {@code createSearchIndexes} on an existing name is a no-op -- so an index written by an older JavAI
     * would keep its old definition forever and answer every narrowed query with
     * "Path 'taggableType' needs to be indexed as filter". This is not hypothetical: it is what the e2e
     * suite's long-lived container did the first time it met this feature.
     *
     * <p>The stale index is reproduced from whatever JavAI actually created -- name and collection read back
     * off the server, not hard-coded -- so the test cannot pass by building something the production code
     * never looks at.
     */
    @Test
    void mongoMigratesATagIndexThatPredatesNarrowing() {
        try (MongoClient client = MongoClients.create(mongoUri())) {
            MongoDatabase database = client.getDatabase(DATABASE);

            // Find every index JavAI created with a taggableType filter path -- both tag indexes have one --
            // reading name, collection and vector definition back off the server rather than hard-coding
            // them, so this cannot pass by breaking something the production code never looks at.
            record StaleTarget(String collection, String index, Document vectorField) {
            }
            List<StaleTarget> targets = new ArrayList<>();
            for (String candidate : database.listCollectionNames()) {
                for (Document index : database.getCollection(candidate).listSearchIndexes()) {
                    Document definition = index.get("latestDefinition", Document.class);
                    List<Document> fields = definition == null
                            ? List.of() : definition.getList("fields", Document.class, List.of());
                    boolean narrowable = fields.stream().anyMatch(field ->
                            "filter".equals(field.getString("type"))
                                    && "taggableType".equals(field.getString("path")));
                    if (narrowable) {
                        targets.add(new StaleTarget(candidate, index.getString("name"), fields.stream()
                                .filter(field -> "vector".equals(field.getString("type")))
                                .findFirst().orElseThrow()));
                    }
                }
            }
            assertEquals(2, targets.size(), "the seeded fixture must have left exactly two narrowable "
                    + "tag-vector indexes (tag-summary and tag-text) for this test to regress -- a different "
                    + "count means the index shape changed and this test is checking something else: "
                    + targets);

            // Put the store back the way an older JavAI would have left it: same collections, same index
            // names, vector field only, no filter path. Both, so neither query can pass by accident.
            for (StaleTarget target : targets) {
                database.runCommand(new Document("dropSearchIndex", target.collection())
                        .append("name", target.index()));
                database.runCommand(new Document("createSearchIndexes", target.collection())
                        .append("indexes", List.of(new Document("name", target.index())
                                .append("type", "vectorSearch")
                                .append("definition", new Document("fields", List.of(target.vectorField()))))));
            }

            // Confirm the store really is in the pre-narrowing state before asking it a narrowed question --
            // otherwise a recreate that had not taken effect would let this pass without migrating anything.
            for (StaleTarget target : targets) {
                assertFalse(declaresTaggableTypeFilter(database, target.collection(), target.index()),
                        "the " + target.index() + " index should now look the way an older JavAI left it");
            }

            // A repository that has not already cached these indexes as ensured -- see mongoConfig().
            JavAITagRepository migrating = new JavAITagRepository(
                    JavAIPI.repository(TagRepository.class, mongoConfig()), mongoConfig());
            EmbeddingVector reference = provider.reference();

            JavAIList<TaggableRef> bySummary = await(
                    () -> migrating.nearestByTagSimilarity(reference, 2, List.of(TestTextedNote.class)),
                    hits -> hits.size() == 2);
            assertEquals(2, bySummary.size(), "the narrowed tag-summary search must succeed against a store "
                    + "whose index predates the filter path -- migrated on the way, not left to fail");
            assertTrue(bySummary.stream().allMatch(ref ->
                    ref.taggableType().equals(TestTextedNote.class.getName())));

            JavAIList<TaggableRef> byText = await(
                    () -> migrating.nearestByTagText(reference, 2, List.of(TestTextedNote.class)),
                    hits -> hits.size() == 2);
            assertEquals(2, byText.size(), "and the tag-text index migrates on the same path");
            assertTrue(byText.stream().allMatch(ref ->
                    ref.taggableType().equals(TestTextedNote.class.getName())));
        }
    }

    /** Whether {@code indexName} currently declares {@code taggableType} as a filter field, read straight
     *  off the server -- the state that decides whether a narrowed {@code $vectorSearch} can run at all. */
    private static boolean declaresTaggableTypeFilter(MongoDatabase database, String collection, String indexName) {
        for (Document index : database.getCollection(collection).listSearchIndexes()) {
            if (!indexName.equals(index.getString("name"))) {
                continue;
            }
            Document definition = index.get("latestDefinition", Document.class);
            List<Document> fields = definition == null
                    ? List.of() : definition.getList("fields", Document.class, List.of());
            return fields.stream().anyMatch(field -> "filter".equals(field.getString("type"))
                    && "taggableType".equals(field.getString("path")));
        }
        return false;
    }

    /** Every claim OMI-460 makes about a narrowed tag index, applied to one store. */
    private void assertNarrowing(Fixture fixture) {
        JavAITagRepository tagging = fixture.tagging();
        EmbeddingVector reference = provider.reference();

        // 0. The trap is real in this fixture: unnarrowed, the nearest two are both Things.
        JavAIList<TaggableRef> unnarrowed = await(
                () -> tagging.tagSimilarityIndex().nearestN(reference, 2), hits -> hits.size() == 2);
        assertEquals(List.of(TestTextedThing.class.getName(), TestTextedThing.class.getName()),
                unnarrowed.stream().map(TaggableRef::taggableType).toList(),
                "the fixture must actually hide the notes behind the things, or nothing below is a test");

        // 1. Narrowed, the same top-2 question returns two Notes -- narrowing ran before the limit.
        JavAIList<TaggableRef> notes = await(
                () -> tagging.nearestByTagSimilarity(reference, 2, List.of(TestTextedNote.class)),
                hits -> hits.size() == 2);
        assertEquals(2, notes.size(), "the nearest 2 notes must be 2 notes -- a rank-then-filter "
                + "implementation returns none here, and an over-fetching one returns them only by luck");
        assertEquals(List.of(fixture.notes().get(0), fixture.notes().get(1)),
                notes.stream().map(TaggableRef::taggableId).toList(), "nearest-first within the type");

        // 2. The chained spelling is the same query.
        JavAIList<TaggableRef> chained = tagging.tagSimilarityIndex()
                .ofType(TestTextedNote.class).nearestN(reference, 2);
        assertEquals(List.copyOf(notes), List.copyOf(chained));

        // 3. Ranked, in closed form -- this is what pins each store's score conversion to raw cosine.
        List<Ranked<TaggableRef>> ranked = tagging.tagSimilarityIndex()
                .ofType(TestTextedNote.class).nearestNRanked(reference, 3);
        assertEquals(3, ranked.size());
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(NOTE_NEAREST), ranked.get(0).similarity(), 1e-4);
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(NOTE_SECOND), ranked.get(1).similarity(), 1e-4);
        assertEquals(ScriptedEmbeddingProvider.expectedSimilarity(NOTE_THIRD), ranked.get(2).similarity(), 1e-4);
        assertEquals(notes.stream().map(TaggableRef::taggableId).toList(),
                ranked.subList(0, 2).stream().map(hit -> hit.entity().taggableId()).toList(),
                "the ranked and unranked forms are one query and cannot order differently");

        // 4. size() counts the narrowed view, which is what filterByMinSimilarity bounds its fetch by.
        VectorIndex<TaggableRef> noteIndex = tagging.tagSimilarityIndex().ofType(TestTextedNote.class);
        assertEquals(3, noteIndex.size());
        assertEquals(6, tagging.tagSimilarityIndex().size(), "the unnarrowed index still spans both types");

        JavAIList<TaggableRef> aboveThreshold = noteIndex.filterByMinSimilarity(reference,
                ScriptedEmbeddingProvider.expectedSimilarity(NOTE_SECOND) - 1e-3);
        assertEquals(2, aboveThreshold.size());
        assertTrue(aboveThreshold.stream().allMatch(ref -> ref.taggableType().equals(TestTextedNote.class.getName())),
                "every Thing clears this threshold on similarity alone and is still excluded -- the type "
                        + "restriction is applied before the threshold, not after it");
        assertFalse(aboveThreshold.stream().anyMatch(ref -> ref.taggableId().equals(fixture.notes().get(2))));

        // 5. The tag-text index narrows on identical terms.
        JavAIList<TaggableRef> textNotes = await(
                () -> tagging.nearestByTagText(reference, 2, List.of(TestTextedNote.class)),
                hits -> hits.size() == 2);
        assertEquals(List.of(fixture.notes().get(0), fixture.notes().get(1)),
                textNotes.stream().map(TaggableRef::taggableId).toList());
        assertEquals(3, tagging.tagTextIndex().ofType(TestTextedNote.class).size());

        // 6. Narrowing intersects, and narrowing to nothing matches nothing.
        assertEquals(0, tagging.tagSimilarityIndex()
                .ofType(TestTextedNote.class).ofType(TestTextedThing.class).size());
        VectorIndex<TaggableRef> nothing = tagging.tagSimilarityIndex().ofType(List.of());
        assertEquals(0, nothing.size());
        assertTrue(nothing.nearestN(reference, 5).isEmpty(),
                "naming no types is not a way to say 'everything'");
    }

    /**
     * MongoDB Search's {@code $vectorSearch} index updates near-real-time rather than synchronously with the
     * write -- the same eventual-consistency gap {@code TaggingE2ETest.awaitContainsRef} documents. Harmless
     * on Postgres and Neo4j, where the first attempt already satisfies the condition.
     */
    private static <T> T await(Supplier<T> query, java.util.function.Predicate<T> satisfied) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (true) {
            T result = query.get();
            if (satisfied.test(result) || Instant.now().isAfter(deadline)) {
                return result;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
        }
    }
}
