package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JavAITagRepository#applyClassification} -- the reconciliation half of {@code classify}, reachable
 * without a {@link dev.xtrafe.javai.completion.Cortex} -- plus the proxy hazard {@code refOf} used to have.
 *
 * <p>Deliberately constructed with the <b>two-argument</b> constructor, so every test here proves the path
 * genuinely needs no LLM: a {@code cortex()} call anywhere in it would throw rather than silently succeed.
 * A real Postgres container is still required for the same reason
 * {@link JavAITaggingClassificationE2ETest} needs one -- the diff under test reads and writes real
 * {@code source = "auto"} associations through a real {@link TaggingBackend}.
 */
@Testcontainers
class JavAITaggingExternalClassificationE2ETest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TagRepository tagRepository;
    private static TagSetRepository tagSetRepository;
    private static TestThingRepository thingRepository;
    private static ThingOwnerRepository ownerRepository;
    private static JavAITagRepository tagging;

    /** Counts {@code findById} calls made through the repository {@link JavAITagRepository} wraps. */
    private static final AtomicInteger FIND_BY_ID_CALLS = new AtomicInteger();

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        // Every repository realized before the first call, so Hibernate's immutable metadata is built once
        // knowing all four entity types (see doc/spec/persistence-bridge.md's "Entity registration").
        tagRepository = JavAIPI.repository(TagRepository.class, config);
        tagSetRepository = JavAIPI.repository(TagSetRepository.class, config);
        thingRepository = JavAIPI.repository(TestThingRepository.class, config);
        ownerRepository = JavAIPI.repository(ThingOwnerRepository.class, config);

        // No Cortex: every test in this class must reach applyClassification without one.
        tagging = new JavAITagRepository(countingFindById(tagRepository), config);
    }

    // ---- the reconciliation contract, without an LLM ------------------------------------------------

    @Test
    void appliesTagsWithSourceAutoWithoutACortex() {
        TagSet perception = tagSetRepository.save(new TagSet("perception"));
        Tag cat = tagRepository.save(new Tag(perception, "en", "Cat"));
        Tag sofa = tagRepository.save(new Tag(perception, "en", "Sofa"));
        TestThing asset = thingRepository.save(new TestThing("a photo"));

        ClassificationResult result = tagging.applyClassification(asset, perception,
                List.of(applied(cat, 0.91), applied(sofa, 0.42)));

        assertEquals(2, result.appliedTags().size());
        assertTrue(tagging.hasTag(asset, cat));
        assertTrue(tagging.hasTag(asset, sofa));
        assertEquals(2, tagging.tagsOf(asset).size());
    }

    @Test
    void removesPreviousAutoTagsNoLongerReturned() {
        TagSet perception = tagSetRepository.save(new TagSet("perception2"));
        Tag cat = tagRepository.save(new Tag(perception, "en", "Cat"));
        Tag dog = tagRepository.save(new Tag(perception, "en", "Dog"));
        TestThing asset = thingRepository.save(new TestThing("a photo"));

        tagging.applyClassification(asset, perception, List.of(applied(cat, 0.9)));
        assertTrue(tagging.hasTag(asset, cat));

        // A re-run of the same model over re-processed bytes: the previous auto tag must retract.
        ClassificationResult result = tagging.applyClassification(asset, perception, List.of(applied(dog, 0.8)));

        assertFalse(tagging.hasTag(asset, cat), "cat was auto-applied before and not returned this time");
        assertTrue(tagging.hasTag(asset, dog));
        assertEquals(1, result.appliedTags().size());
    }

    @Test
    void neverTouchesManuallyAppliedTags() {
        TagSet perception = tagSetRepository.save(new TagSet("perception3"));
        Tag cat = tagRepository.save(new Tag(perception, "en", "Cat"));
        Tag curated = tagRepository.save(new Tag(perception, "en", "Curated"));
        TestThing asset = thingRepository.save(new TestThing("a photo"));

        tagging.addTag(asset, curated);
        tagging.applyClassification(asset, perception, List.of(applied(cat, 0.9)));
        assertTrue(tagging.hasTag(asset, curated), "a manual tagging must survive an external classification");

        // ...including when the classifier retracts everything it had previously applied.
        tagging.applyClassification(asset, perception, List.of());
        assertTrue(tagging.hasTag(asset, curated));
        assertFalse(tagging.hasTag(asset, cat));
    }

    @Test
    void anEmptyResultRetractsEverythingAutomaticAndIsNotAnError() {
        TagSet perception = tagSetRepository.save(new TagSet("perception4"));
        Tag cat = tagRepository.save(new Tag(perception, "en", "Cat"));
        TestThing asset = thingRepository.save(new TestThing("a photo of nothing recognisable"));

        tagging.applyClassification(asset, perception, List.of(applied(cat, 0.9)));
        ClassificationResult result = tagging.applyClassification(asset, perception, List.of());

        assertTrue(result.appliedTags().isEmpty());
        assertTrue(tagging.tagsOf(asset).isEmpty());
    }

    @Test
    void reapplyingTheSameResultIsIdempotent() {
        TagSet perception = tagSetRepository.save(new TagSet("perception5"));
        Tag cat = tagRepository.save(new Tag(perception, "en", "Cat"));
        TestThing asset = thingRepository.save(new TestThing("a photo"));

        // At-least-once delivery redelivers the same event; it must converge, not accumulate.
        tagging.applyClassification(asset, perception, List.of(applied(cat, 0.9)));
        tagging.applyClassification(asset, perception, List.of(applied(cat, 0.9)));

        assertEquals(1, tagging.tagsOf(asset).size());
    }

    @Test
    void aTagFromAnotherTagSetIsRefusedRatherThanSilentlyApplied() {
        TagSet perception = tagSetRepository.save(new TagSet("perception6"));
        TagSet elsewhere = tagSetRepository.save(new TagSet("elsewhere6"));
        Tag foreign = tagRepository.save(new Tag(elsewhere, "en", "Foreign"));
        TestThing asset = thingRepository.save(new TestThing("a photo"));

        // Not merely tidiness: the removal scan is scoped to this set, so an off-set tag applied as `auto`
        // could never be retracted by any later classification of either set.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> tagging.applyClassification(asset, perception, List.of(applied(foreign, 0.9))));
        assertTrue(thrown.getMessage().contains("foreign"), thrown.getMessage());
        assertTrue(tagging.tagsOf(asset).isEmpty(), "nothing may be written when the batch is refused");
    }

    @Test
    void aRepeatedTagInOneResultIsReportedOnce() {
        TagSet perception = tagSetRepository.save(new TagSet("perception7"));
        Tag cat = tagRepository.save(new Tag(perception, "en", "Cat"));
        TestThing asset = thingRepository.save(new TestThing("a photo"));

        ClassificationResult result = tagging.applyClassification(asset, perception,
                List.of(applied(cat, 0.9), applied(cat, 0.4)));

        assertEquals(1, result.appliedTags().size(), "one association must not be reported as two");
        assertEquals(1, tagging.tagsOf(asset).size());
    }

    // ---- the cost claim, pinned rather than asserted in prose ---------------------------------------

    @Test
    void recomputesTheTagSummaryVectorOncePerCallRatherThanOncePerTag() {
        TagSet perception = tagSetRepository.save(new TagSet("perception-cost"));
        List<Tag> tags = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tags.add(tagRepository.save(new Tag(perception, "en", "Perceived " + i)));
        }
        TestThing batched = thingRepository.save(new TestThing("batched"));
        TestThing looped = thingRepository.save(new TestThing("looped"));

        List<ClassificationResult.AppliedTag> results = new ArrayList<>();
        for (Tag tag : tags) {
            results.add(applied(tag, 0.5));
        }

        FIND_BY_ID_CALLS.set(0);
        tagging.applyClassification(batched, perception, results);
        int batchedCalls = FIND_BY_ID_CALLS.get();

        FIND_BY_ID_CALLS.set(0);
        for (Tag tag : tags) {
            tagging.addTag(looped, tag, 0.5);
        }
        int loopedCalls = FIND_BY_ID_CALLS.get();

        // One recomputation resolving ten associations, versus ten recomputations resolving 1..10 -- the
        // triangular number is the whole point: looping is quadratic in tags-per-instance, and an image
        // tagger returns tags by the dozen.
        assertEquals(10, batchedCalls, "one recomputation, resolving each of the ten associations once");
        assertEquals(55, loopedCalls, "1+2+...+10 -- a recomputation per addTag, each resolving every"
                + " association applied so far");

        // ...and the two instances end up in identical states, so this is purely a cost difference.
        assertEquals(tagging.tagsOf(batched).size(), tagging.tagsOf(looped).size());
        assertEquals(10, tagging.tagsOf(batched).size());
    }

    // ---- the proxy hazard ---------------------------------------------------------------------------

    @Test
    void taggingThroughALazyAssociationFilesTheRealEntityType() {
        TagSet perception = tagSetRepository.save(new TagSet("perception-proxy"));
        Tag cat = tagRepository.save(new Tag(perception, "en", "Cat"));
        TestThing asset = thingRepository.save(new TestThing("reached through an owner"));
        ownerRepository.save(new ThingOwner("owner", asset));

        // Inside a unit of work, `owner.getThing()` is an uninitialized Hibernate proxy: a generated
        // subclass whose getClass().getName() is Target$HibernateProxy$xyz and whose @Id *field* is null.
        // Before refOf resolved it, this wrote an association nothing could ever find again.
        JavAIPI.inTransaction(config, () -> {
            ThingOwner loaded = ownerRepository.findAll().stream()
                    .filter(each -> "owner".equals(each.getLabel()))
                    .findFirst()
                    .orElseThrow();
            TestThing viaAssociation = loaded.getThing();
            // Without this the test could silently degrade into a no-op: if Hibernate ever handed back the
            // real instance here (a final entity class, an eager mapping, a session-cache hit), the rest
            // would pass while proving nothing about proxies at all.
            assertFalse(viaAssociation.getClass().equals(TestThing.class),
                    "this test is only meaningful if the association really yields a generated subclass");
            assertTrue(viaAssociation.getClass().getName().contains("HibernateProxy"),
                    "expected a Hibernate proxy, got " + viaAssociation.getClass().getName());
            tagging.addTag(viaAssociation, cat);
        });

        // Asked through the real instance -- which is the only way anything else in the system will ask.
        assertTrue(tagging.hasTag(asset, cat), "a tag applied through a proxy must be visible on the entity");
        JavAIList<Tag> tags = tagging.tagsOf(asset);
        assertEquals(1, tags.size());
        assertEquals("cat", tags.get(0).getSlug());

        List<TaggableRef> tagged = tagging.taggedWith(cat, List.of(TestThing.class));
        assertEquals(1, tagged.size());
        assertEquals(TestThing.class.getName(), tagged.get(0).taggableType(),
                "the ref must name the entity, never the generated proxy subclass");
    }

    @Test
    void taggingAnUnresolvableDetachedProxyFailsRatherThanWritingAGarbageRef() {
        TagSet perception = tagSetRepository.save(new TagSet("perception-detached"));
        Tag cat = tagRepository.save(new Tag(perception, "en", "Cat"));
        TestThing asset = thingRepository.save(new TestThing("detached owner"));
        ownerRepository.save(new ThingOwner("detached-owner", asset));

        ThingOwner detached = ownerRepository.findAll().stream()
                .filter(each -> "detached-owner".equals(each.getLabel()))
                .findFirst()
                .orElseThrow();

        TestThing unresolvable = detached.getThing();
        assertTrue(unresolvable.getClass().getName().contains("HibernateProxy"),
                "expected a detached Hibernate proxy, got " + unresolvable.getClass().getName());

        // A repository returns a genuinely detached graph (OMI-271), so this proxy can never be resolved.
        // Failing is the correct outcome and a strict improvement on the silent write it replaced: you
        // cannot tag what you cannot identify.
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> tagging.addTag(unresolvable, cat));
        // Pinned so this can never start passing because of some unrelated failure -- it must be the
        // resolution of the proxy that fails, not (say) an NPE somewhere in the tagging path.
        assertTrue(thrown instanceof LazyInitializationException,
                "expected the proxy resolution itself to fail, got " + thrown);
        assertTrue(tagging.tagsOf(asset).isEmpty(), "nothing may be written for an unidentifiable instance");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static ClassificationResult.AppliedTag applied(Tag tag, double affinity) {
        return new ClassificationResult.AppliedTag(tag, affinity, null);
    }

    /**
     * Wraps {@code real} so every {@code findById} it serves is counted.
     *
     * <p>A dynamic proxy rather than a hand-written decorator, deliberately: {@link TagRepository} inherits
     * a dozen methods from {@code JavAIRepository}, none of which this test has an opinion about, and a
     * hand-written one would need editing every time that interface grows.
     */
    private static TagRepository countingFindById(TagRepository real) {
        return (TagRepository) Proxy.newProxyInstance(
                TagRepository.class.getClassLoader(),
                new Class<?>[] { TagRepository.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        FIND_BY_ID_CALLS.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
