package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.VectorizableString;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins OMI-302's "Done means" list against a real pgvector container -- see doc/spec/tagging.md's
 * "Taggregate: derived taggings for containers". Containers ({@link TestAlbum}/{@link TestWovenAlbum}) are
 * deliberately never persisted: on this backend every Taggregate store is a dedicated table keyed by ref,
 * so the lineage rule's minimum ({@code implements Taggable} + {@code @Id UUID}) is also literally all a
 * container needs -- itself part of what these tests pin.
 */
@Testcontainers
class JavAITaggregatePostgresE2ETest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TagRepository tagRepository;
    private static TagSetRepository tagSetRepository;
    private static TestThingRepository thingRepository;
    private static TestTextedThingRepository textedThingRepository;
    private static RecordingEmbeddingProvider provider;
    private static JavAITagRepository tagging;
    /** A second, independent backend handle for asserting pending-set state directly. */
    private static TaggingBackendHibernatePostgres inspection;

    @BeforeAll
    static void configure() {
        provider = new RecordingEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIRuntime.configureEmbeddingProvider(provider);
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        tagRepository = JavAIPI.repository(TagRepository.class, config);
        tagSetRepository = JavAIPI.repository(TagSetRepository.class, config);
        thingRepository = JavAIPI.repository(TestThingRepository.class, config);
        textedThingRepository = JavAIPI.repository(TestTextedThingRepository.class, config);
        tagging = new JavAITagRepository(tagRepository, config);
        inspection = new TaggingBackendHibernatePostgres(config);
    }

    private static TaggableRef refOf(Object instance) {
        return new TaggableRef(instance.getClass().getName(), TaggingReflection.idOf(instance));
    }

    private static Tagging aggregateRowFor(List<Tagging> taggings, Tag tag) {
        return taggings.stream()
                .filter(t -> Tagging.SOURCE_AGGREGATE.equals(t.source()) && t.tag().getId().equals(tag.getId()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no aggregate row for '" + tag.getSlug() + "' among " + taggings.size() + " taggings"));
    }

    @Test
    void plainContainerAggregatesWovenMembers() {
        TagSet set = tagSetRepository.save(new TagSet("agg-plain-container"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Recurring Topic"));
        Tag memberOne = tagRepository.save(new Tag(set, "en", "Member One Alpha"));
        Tag memberTwo = tagRepository.save(new Tag(set, "en", "Member Two Alpha"));
        tagging.addTag(memberOne, topic);   // woven members (Tag is build-time woven) carrying taggings
        tagging.addTag(memberTwo, topic);

        TestAlbum album = new TestAlbum("plain-over-woven");
        album.getMembers().add(memberOne);
        album.getMembers().add(memberTwo);
        tagging.reconcileTaggregate(album);

        List<Tagging> taggings = tagging.taggingsOf(album);
        Tagging aggregate = aggregateRowFor(taggings, topic);
        assertEquals(1.0, aggregate.affinity(), 1e-9, "tag on 2/2 members, null affinity counting 1.0");
    }

    @Test
    void wovenContainerAggregatesPlainMembers() {
        TagSet set = tagSetRepository.save(new TagSet("agg-woven-container"));
        Tag topic = tagRepository.save(new Tag(set, "en", "Woven Container Topic"));
        TestThing plainA = thingRepository.save(new TestThing("plain-a"));
        TestThing plainB = thingRepository.save(new TestThing("plain-b"));
        tagging.addTag(plainA, topic, 0.5);
        tagging.addTag(plainB, topic, 0.7);

        TestWovenAlbum album = new TestWovenAlbum("woven-over-plain");
        album.getMembers().add(plainA);
        album.getMembers().add(plainB);
        tagging.reconcileTaggregate(album);

        Tagging aggregate = aggregateRowFor(tagging.taggingsOf(album), topic);
        assertEquals(0.6, aggregate.affinity(), 1e-9);
    }

    @Test
    void aggregateAffinityIsTheMeanContributionOverMembers() {
        TagSet set = tagSetRepository.save(new TagSet("agg-formula"));
        Tag everywhere = tagRepository.save(new Tag(set, "en", "Formula Everywhere"));
        Tag rare = tagRepository.save(new Tag(set, "en", "Formula Rare"));
        Tag binary = tagRepository.save(new Tag(set, "en", "Formula Binary"));

        TestAlbum album = new TestAlbum("formula");
        List<TestThing> members = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TestThing member = thingRepository.save(new TestThing("formula-" + i));
            members.add(member);
            album.getMembers().add(member);
            tagging.addTag(member, everywhere, 0.99);
        }
        tagging.addTag(members.get(0), rare, 0.99);
        tagging.addTag(members.get(0), binary);   // null affinity -> counts 1.0

        tagging.reconcileTaggregate(album);
        List<Tagging> taggings = tagging.taggingsOf(album);
        assertEquals(0.99, aggregateRowFor(taggings, everywhere).affinity(), 1e-9, "10/10 members at 0.99");
        assertEquals(0.099, aggregateRowFor(taggings, rare).affinity(), 1e-9, "1/10 members at 0.99");
        assertEquals(0.1, aggregateRowFor(taggings, binary).affinity(), 1e-9, "1/10, null affinity counting 1.0");

        // An untagged member dilutes everything.
        album.getMembers().add(thingRepository.save(new TestThing("formula-untagged")));
        tagging.reconcileTaggregate(album);
        assertEquals(0.99 * 10 / 11, aggregateRowFor(tagging.taggingsOf(album), everywhere).affinity(), 1e-9);
    }

    @Test
    void recomputeRewritesOnlyItsOwnProvenanceAndRerunningIsANoOp() {
        TagSet set = tagSetRepository.save(new TagSet("agg-provenance"));
        Tag aggregated = tagRepository.save(new Tag(set, "en", "Provenance Aggregated"));
        Tag manual = tagRepository.save(new Tag(set, "en", "Provenance Manual"));
        Tag auto = tagRepository.save(new Tag(set, "en", "Provenance Auto"));

        TestAlbum album = new TestAlbum("provenance");
        TestThing member = thingRepository.save(new TestThing("provenance-member"));
        tagging.addTag(member, aggregated, 0.8);
        album.getMembers().add(member);

        // Manual and auto rows on the container itself, which no recompute may touch.
        tagging.addTag(album, manual, 0.42);
        tagging.applyClassification(album, set,
                List.of(new ClassificationResult.AppliedTag(auto, 0.66, null)));

        CountingTaggingBackend counting = new CountingTaggingBackend(new TaggingBackendHibernatePostgres(config));
        JavAITagRepository countingTagging = new JavAITagRepository(tagRepository, counting, null);
        countingTagging.reconcileTaggregate(album);

        List<Tagging> taggings = countingTagging.taggingsOf(album);
        assertEquals(0.8, aggregateRowFor(taggings, aggregated).affinity(), 1e-9);
        Tagging manualRow = taggings.stream().filter(t -> t.tag().getId().equals(manual.getId())).findFirst().orElseThrow();
        Tagging autoRow = taggings.stream().filter(t -> t.tag().getId().equals(auto.getId())).findFirst().orElseThrow();
        assertEquals(Tagging.SOURCE_MANUAL, manualRow.source());
        assertEquals(0.42, manualRow.affinity(), 1e-9);
        assertEquals(Tagging.SOURCE_AUTO, autoRow.source());
        assertEquals(0.66, autoRow.affinity(), 1e-9);

        // Re-running against unchanged members writes nothing at all -- the diff discipline, asserted
        // structurally rather than by re-reading rows.
        counting.reset();
        countingTagging.reconcileTaggregate(album);
        assertEquals(0, counting.callsTo("addTag"), "no-op reconcile must not rewrite aggregate rows");
        assertEquals(0, counting.callsTo("removeTag"));
        assertEquals(0, counting.callsTo("upsertTagSummaryVector"), "unchanged rows need no vector recompute");
    }

    @Test
    void recomputeReadsMemberTaggingsInOneBatchedQuery() {
        TagSet set = tagSetRepository.save(new TagSet("agg-cost"));
        Tag tag = tagRepository.save(new Tag(set, "en", "Cost Tag"));
        TestAlbum album = new TestAlbum("cost");
        for (int i = 0; i < 5; i++) {
            TestThing member = thingRepository.save(new TestThing("cost-" + i));
            tagging.addTag(member, tag, 0.5);
            album.getMembers().add(member);
        }

        CountingTaggingBackend counting = new CountingTaggingBackend(new TaggingBackendHibernatePostgres(config));
        JavAITagRepository countingTagging = new JavAITagRepository(tagRepository, counting, null);
        countingTagging.reconcileTaggregate(album);

        assertEquals(1, counting.callsTo("associationsOfAll"), "member taggings must be one batched read");
        assertEquals(2, counting.callsTo("associationsOf"),
                "exactly two single-ref reads: the aggregate-row diff and the derived-vector recompute");
        assertEquals(1, counting.callsTo("upsertTagSummaryVector"), "tag summary recomputes once per reconcile");
    }

    @Test
    void memberMutationAtEachChokePointMarksContainersPendingTransitively() {
        TagSet set = tagSetRepository.save(new TagSet("agg-pending"));
        Tag added = tagRepository.save(new Tag(set, "en", "Pending Added"));
        Tag classified = tagRepository.save(new Tag(set, "en", "Pending Classified"));

        TestThing member = thingRepository.save(new TestThing("pending-member"));
        TestAlbum album = new TestAlbum("pending-album");
        album.getMembers().add(member);
        TestAlbum shelf = new TestAlbum("pending-shelf");
        shelf.getMembers().add(album);
        tagging.reconcileTaggregate(album);
        tagging.reconcileTaggregate(shelf);   // snapshots exist; nothing pending yet for either

        TaggableRef albumRef = refOf(album);
        TaggableRef shelfRef = refOf(shelf);
        assertTrue(inspection.claimTaggregatePendingFor(albumRef).isEmpty());

        tagging.addTag(member, added, 0.9);
        assertFalse(inspection.claimTaggregatePendingFor(albumRef).isEmpty(), "addTag must mark the container");
        assertFalse(inspection.claimTaggregatePendingFor(shelfRef).isEmpty(),
                "marking must be transitive through one nesting level");

        // The pending marks are consumed by reads holding the objects, and nesting composes: the shelf
        // aggregates the album's own aggregate rows.
        assertEquals(0.9, aggregateRowFor(tagging.taggingsOf(album), added).affinity(), 1e-9);
        assertEquals(0.9, aggregateRowFor(tagging.taggingsOf(shelf), added).affinity(), 1e-9);
        assertTrue(inspection.claimTaggregatePendingFor(albumRef).isEmpty(), "the read drains its own marks");

        // The other two choke points mark identically.
        tagging.applyClassification(member, set,
                List.of(new ClassificationResult.AppliedTag(classified, 0.5, null)));
        assertFalse(inspection.claimTaggregatePendingFor(albumRef).isEmpty(), "applyClassification must mark");
        tagging.taggingsOf(album);
        tagging.removeTag(member, added);
        assertFalse(inspection.claimTaggregatePendingFor(albumRef).isEmpty(), "removeTag must mark");
        List<Tagging> after = tagging.taggingsOf(album);
        assertTrue(after.stream().noneMatch(t -> t.tag().getId().equals(added.getId())),
                "removed member tag must leave the aggregate");
    }

    @Test
    void aContainmentCycleTerminatesAndLogs() {
        TagSet set = tagSetRepository.save(new TagSet("agg-cycle"));
        Tag tag = tagRepository.save(new Tag(set, "en", "Cycle Tag"));
        TestThing member = thingRepository.save(new TestThing("cycle-member"));

        TestAlbum albumX = new TestAlbum("cycle-x");
        TestAlbum albumY = new TestAlbum("cycle-y");
        albumX.getMembers().add(member);
        albumX.getMembers().add(albumY);
        albumY.getMembers().add(albumX);
        tagging.reconcileTaggregate(albumX);
        tagging.reconcileTaggregate(albumY);

        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(JavAITagRepository.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            tagging.addTag(member, tag);   // terminates despite X <-> Y mutual containment
        } finally {
            logger.removeHandler(handler);
        }
        assertFalse(inspection.claimTaggregatePendingFor(refOf(albumX)).isEmpty());
        assertFalse(inspection.claimTaggregatePendingFor(refOf(albumY)).isEmpty());
        assertTrue(records.stream().anyMatch(record -> record.getMessage().contains("cycle")),
                "a detected containment cycle must be logged");
    }

    @Test
    void membershipDriftIsSeenByTaggingsOfButNotBySearchAndTheSweepTruesItUp() {
        TagSet set = tagSetRepository.save(new TagSet("agg-drift"));
        Tag original = tagRepository.save(new Tag(set, "en", "Drift Original"));
        Tag late = tagRepository.save(new Tag(set, "en", "Drift Late"));

        // Sweep path: search stays stale after a membership change until the sweep, with a loader, runs.
        TestThing first = thingRepository.save(new TestThing("drift-first"));
        tagging.addTag(first, original);
        TestAlbum swept = new TestAlbum("drift-swept");
        swept.getMembers().add(first);
        tagging.reconcileTaggregate(swept);

        TestThing latecomer = thingRepository.save(new TestThing("drift-late"));
        tagging.addTag(latecomer, late);
        swept.getMembers().add(latecomer);   // membership drift: no repository call sees this

        TaggableRef sweptRef = refOf(swept);
        JavAIList<TaggableRef> stale = tagging.taggedWith(late, List.of(TestAlbum.class));
        assertFalse(stale.contains(sweptRef), "a search-only path must not recompute");

        tagging.markTaggregateStale(swept);
        Map<TaggableRef, Object> loadable = Map.of(sweptRef, swept);
        int reconciled = tagging.reconcilePendingTaggregates(100, loadable::get);
        assertTrue(reconciled >= 1);
        assertTrue(tagging.taggedWith(late, List.of(TestAlbum.class)).contains(sweptRef),
                "the sweep must true up the drifted aggregate");

        // Read path: taggingsOf holding the object detects the same drift by snapshot comparison alone.
        TestAlbum read = new TestAlbum("drift-read");
        read.getMembers().add(first);
        tagging.reconcileTaggregate(read);
        read.getMembers().add(latecomer);
        List<Tagging> taggings = tagging.taggingsOf(read);
        assertNotNull(aggregateRowFor(taggings, late));
    }

    @Test
    void concurrentReconcilesOfOneAggregateConverge() throws Exception {
        TagSet set = tagSetRepository.save(new TagSet("agg-concurrent"));
        Tag tag = tagRepository.save(new Tag(set, "en", "Concurrent Tag"));
        TestAlbum album = new TestAlbum("concurrent");
        for (int i = 0; i < 4; i++) {
            TestThing member = thingRepository.save(new TestThing("concurrent-" + i));
            tagging.addTag(member, tag, 0.6);
            album.getMembers().add(member);
        }

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> failures = new ArrayList<>();
        Runnable reconcile = () -> {
            try {
                barrier.await();
                tagging.reconcileTaggregate(album);
            } catch (Throwable t) {
                synchronized (failures) {
                    failures.add(t);
                }
            }
        };
        Thread one = new Thread(reconcile);
        Thread two = new Thread(reconcile);
        one.start();
        two.start();
        one.join();
        two.join();
        assertTrue(failures.isEmpty(), () -> "concurrent reconcile failed: " + failures);

        List<Tagging> taggings = tagging.taggingsOf(album);
        long aggregateRows = taggings.stream().filter(t -> Tagging.SOURCE_AGGREGATE.equals(t.source())).count();
        assertEquals(1, aggregateRows);
        assertEquals(0.6, aggregateRowFor(taggings, tag).affinity(), 1e-9);
    }

    @Test
    void tagTextIsDeterministicOrderedAndServedByTheRepository() {
        TagSet set = tagSetRepository.save(new TagSet("text-order"));
        Tag alpine = tagRepository.save(new Tag(set, "en", "Alpine Lake"));
        Tag zebra = tagRepository.save(new Tag(set, "en", "Zebra"));
        Tag dirtRoad = tagRepository.save(new Tag(set, "en", "Dirt Road"));

        TestAlbum album = new TestAlbum("text-order");
        TestThing m1 = thingRepository.save(new TestThing("text-m1"));
        TestThing m2 = thingRepository.save(new TestThing("text-m2"));
        TestThing m3 = thingRepository.save(new TestThing("text-m3"));
        tagging.addTag(m1, alpine, 0.9);
        tagging.addTag(m2, zebra, 0.9);
        tagging.addTag(m3, dirtRoad, 0.5);
        album.getMembers().add(m1);
        album.getMembers().add(m2);
        album.getMembers().add(m3);

        tagging.reconcileTaggregate(album);
        // Display names ("Dirt Road", never "dirt-road"), affinity-descending, ties broken by slug.
        assertEquals("Alpine Lake, Zebra, Dirt Road", tagging.tagText(album));
        assertFalse(tagging.tagTextVector(album).isAbsent());

        // Deterministic: an identical recompute yields the identical string.
        tagging.reconcileTaggregate(album);
        assertEquals("Alpine Lake, Zebra, Dirt Road", tagging.tagText(album));
    }

    @Test
    void tagTextIsCappedAtTheTopFiftyByAffinity() {
        TagSet set = tagSetRepository.save(new TagSet("text-cap"));
        TestTextedThing thing = textedThingRepository.save(new TestTextedThing("cap"));
        List<ClassificationResult.AppliedTag> results = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            Tag tag = tagRepository.save(new Tag(set, "en", String.format("Cap Tag %02d", i)));
            results.add(new ClassificationResult.AppliedTag(tag, 1.0 - i * 0.01, null));
        }
        tagging.applyClassification(thing, set, results);

        String text = tagging.tagText(thing);
        String[] entries = text.split(", ");
        assertEquals(50, entries.length, "top-K cap must bound the rendered text");
        assertEquals("Cap Tag 00", entries[0], "highest affinity leads");
        assertEquals("Cap Tag 49", entries[49], "the five weakest tags fall past the cap");
    }

    @Test
    void tagTextIndexRanksAContainerByAnEmbeddedQueryStringEndToEnd() {
        TagSet set = tagSetRepository.save(new TagSet("text-e2e"));
        Tag lake = tagRepository.save(new Tag(set, "en", "Mountain Lake E2E"));
        TestThing member = thingRepository.save(new TestThing("text-e2e-member"));
        tagging.addTag(member, lake);
        TestAlbum album = new TestAlbum("text-e2e");
        album.getMembers().add(member);
        tagging.reconcileTaggregate(album);

        String text = tagging.tagText(album);
        EmbeddingVector query = new VectorizableString(text).vector();
        JavAIList<TaggableRef> nearest = tagging.tagTextIndex().nearestN(query, 1);
        assertEquals(1, nearest.size());
        assertEquals(refOf(album), nearest.get(0));
    }

    @Test
    void typesThatOptIntoNothingCostZeroEmbeds() {
        TagSet set = tagSetRepository.save(new TagSet("zero-embed"));
        Tag tag = tagRepository.save(new Tag(set, "en", "Zero Embed Tag"));
        TestThing warmup = thingRepository.save(new TestThing("zero-embed-warmup"));
        TestThing thing = thingRepository.save(new TestThing("zero-embed"));
        tagging.addTag(warmup, tag);   // warms the tag's stored vectors

        provider.reset();
        tagging.addTag(thing, tag, 0.5);
        tagging.taggingsOf(thing);
        tagging.hasTag(thing, tag);
        assertEquals(0, provider.ledger().totalCalls(),
                "a type declaring nothing must behave identically to today: no embeds, "
                        + provider.ledger().report());
    }

    @Test
    void rankedByTagsReturnsExactSummedScoresAcrossHeterogeneousTagSets() {
        TagSet perception = tagSetRepository.save(new TagSet("ranked-perception"));
        TagSet interests = tagSetRepository.save(new TagSet("ranked-interests"));
        Tag machine = tagRepository.save(new Tag(perception, "en", "Ranked Machine"));
        Tag authored = tagRepository.save(new Tag(interests, "en", "Ranked Authored"));

        TestThing both = thingRepository.save(new TestThing("ranked-both"));
        TestThing binary = thingRepository.save(new TestThing("ranked-binary"));
        TestThing weak = thingRepository.save(new TestThing("ranked-weak"));
        tagging.addTag(both, machine, 0.9);
        tagging.addTag(both, authored, 0.8);
        tagging.addTag(binary, machine);        // null affinity -> 1.0
        tagging.addTag(weak, authored, 0.3);

        List<RankedTaggableRef> ranked = tagging.rankedByTags(
                List.of(machine, authored), List.of(TestThing.class), 10);
        assertEquals(3, ranked.size());
        assertEquals(refOf(both), ranked.get(0).ref());
        assertEquals(1.7, ranked.get(0).similarity(), 1e-9);
        assertEquals(refOf(binary), ranked.get(1).ref());
        assertEquals(1.0, ranked.get(1).similarity(), 1e-9);
        assertEquals(refOf(weak), ranked.get(2).ref());
        assertEquals(0.3, ranked.get(2).similarity(), 1e-9);

        assertEquals(2, tagging.rankedByTags(List.of(machine, authored), List.of(TestThing.class), 2).size());
    }

    @Test
    void tagQueryVectorGivesTheFuzzyCounterpartOfARankedQuery() {
        TagSet set = tagSetRepository.save(new TagSet("query-vector"));
        Tag tag = tagRepository.save(new Tag(set, "en", "Query Vector Tag"));
        EmbeddingVector query = tagging.tagQueryVector(List.of(tag));
        assertFalse(query.isAbsent());
        assertEquals(FakeEmbeddingProvider.DIMS, query.dims());
    }
}
