package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.model.EmbeddingConsistencyMode;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.testsupport.RecordingEmbeddingProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OMI-187's headline reproduction, as a permanent test: <b>seeding N {@code Tag}s into one {@code TagSet}
 * must cost exactly N embedding calls</b> -- one per tag slug -- and nothing more.
 *
 * <h2>What this measures, and why the ticket needed it</h2>
 *
 * omiai-platform's interest catalog (~1,400 tags across 30 sets) could not be seeded in under ten minutes,
 * and the cause was diagnosed twice from source alone, wrongly both times: first as an O(N^2) sibling
 * re-embedding, then as per-{@code save()} overhead with embedding cost dismissed as O(N). Wrapping the
 * provider settled it. Saving 12 tags into one set fired 36 embed calls in the exact repeating sequence
 * {@code [tag.slug, tagSet.slug, ""]} -- 12 correct, 24 wasted:
 *
 * <ul>
 *   <li>the owning {@code TagSet}'s own {@code @Vectorize} slug, re-embedded once per child insert even
 *       though its value never changed, and</li>
 *   <li>the empty string, embedded once per child insert.</li>
 * </ul>
 *
 * <p>Both are pure waste against a network-bound, rate-limited provider. At ~10 ms per embed that is a 3x
 * multiplier on the only genuinely expensive operation in the whole seed path.
 *
 * <h2>Why the fixture values are unique</h2>
 *
 * Every slug in here is globally unique, so the ledger's frequency map maps text back to exactly one
 * (object, field) pair. See {@code EmbeddingLedger}'s javadoc for the full reasoning, and
 * {@code EmbeddingCallInvariantTest} (javai-model) for the same invariant proven with no persistence at
 * all -- the two together separate "the runtime over-embeds" from "persistence makes it over-embed."
 */
@Testcontainers
class TagSetSeedEmbeddingCostTest {

    private static final AtomicLong UNIQUE = new AtomicLong();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TagRepository tagRepository;
    private static TagSetRepository tagSetRepository;

    private RecordingEmbeddingProvider provider;

    @BeforeAll
    static void configureRepositories() {
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        tagRepository = JavAIPI.repository(TagRepository.class, config);
        tagSetRepository = JavAIPI.repository(TagSetRepository.class, config);
    }

    @BeforeEach
    void installRecordingProvider() {
        // IMMEDIATE is the JavAIRuntime default and what omiai-platform was running when OMI-187 was filed.
        JavAIRuntime.configureConsistencyMode(EmbeddingConsistencyMode.IMMEDIATE_CONSISTENCY);
        provider = new RecordingEmbeddingProvider();
        JavAIRuntime.configureEmbeddingProvider(provider);
    }

    private static String unique(String label) {
        return label + "-" + UNIQUE.incrementAndGet();
    }

    /**
     * The seeder's literal hot path: one {@code save()} per tag, in a loop, into an already-persisted set.
     * This is the shape omiai-platform's {@code TagSetSeeder} uses and the shape the ticket measured.
     */
    @Test
    void seedingTagsOneAtATimeEmbedsEachSlugExactlyOnceAndNothingElse() {
        int tagCount = 12;
        TagSet tagSet = tagSetRepository.save(new TagSet(unique("probe-set")));

        // The set's own slug is legitimately embedded by the save above. Reset so the assertion covers only
        // the seeding loop, where the correct cost is one embed per tag and zero for anything else.
        provider.reset();

        List<String> expectedSlugs = new ArrayList<>();
        for (int i = 0; i < tagCount; i++) {
            String displayName = unique("Probe Tag");
            expectedSlugs.add(displayName.toLowerCase().replace(' ', '-'));
            tagRepository.save(new Tag(tagSet, "en", displayName));
        }

        provider.ledger().assertEmbeddedExactlyOnce(expectedSlugs);
        assertEquals(tagCount, provider.ledger().totalCalls(),
                "seeding " + tagCount + " tags must cost exactly " + tagCount + " embeddings");
    }

    /**
     * The recommended bulk path from the ticket: add every tag to the set first, then persist once. It must
     * cost the same N embeddings as the per-tag loop above -- if the two disagree, the difference is waste
     * in whichever one is higher, not a property of the data.
     */
    @Test
    void seedingViaASingleOwnerSaveCostsTheSameAsSeedingTagByTag() {
        int tagCount = 12;
        String setSlug = unique("bulk-set");
        TagSet tagSet = new TagSet(setSlug);

        List<String> expected = new ArrayList<>();
        expected.add(setSlug);
        for (int i = 0; i < tagCount; i++) {
            String displayName = unique("Bulk Tag");
            expected.add(displayName.toLowerCase().replace(' ', '-'));
            new Tag(tagSet, "en", displayName);
        }

        provider.reset();
        tagSetRepository.save(tagSet);
        for (Tag tag : tagSet.getTags()) {
            tagRepository.save(tag);
        }

        provider.ledger().assertEmbeddedExactlyOnce(expected);
    }

    /**
     * Scaling check: the per-tag cost must be flat. The ticket's headline symptom was cost growing with
     * set size, so a constant multiplier is not enough -- doubling the tags must exactly double the
     * embeddings, with no term that grows with the set's current membership.
     */
    @Test
    void embeddingCostIsExactlyLinearInTagCount() {
        assertEquals(2 * embedCountForSeeding(8), embedCountForSeeding(16),
                "embedding cost must be exactly linear in tag count, with no per-set-size term");
    }

    private long embedCountForSeeding(int tagCount) {
        TagSet tagSet = tagSetRepository.save(new TagSet(unique("scaling-set")));
        provider.reset();
        for (int i = 0; i < tagCount; i++) {
            tagRepository.save(new Tag(tagSet, "en", unique("Scaling Tag")));
        }
        return provider.ledger().totalCalls();
    }

    /**
     * The same invariant under every consistency mode, because the invariant is not a mode-specific
     * promise: the modes differ in <em>when</em> a recomputation happens and whether the reader blocks for
     * it, never in how many distinct embeddings the work actually requires.
     *
     * <p>This also discriminates between the two candidate mechanisms for the owner's slug being
     * re-embedded. {@code JavAIRuntime.mustBlockUnderObjectLock} returns true whenever
     * {@code !slot.everComputed()}, regardless of mode -- so if the cause is a <em>fresh instance with an
     * empty cache</em> (Hibernate's {@code merge()} returning a different object, whose woven
     * {@code $javai$state} field is a brand-new {@code DirtyTrackingSupport}), the waste is identical in all
     * three modes. If instead the cause were over-eager dirty propagation, the deferred modes would differ.
     * Identical counts across modes therefore mean cache loss by object identity, not a dirty-flag bug.
     */
    @ParameterizedTest
    @EnumSource(EmbeddingConsistencyMode.class)
    void seedingCostIsIdenticalUnderEveryConsistencyMode(EmbeddingConsistencyMode mode) {
        JavAIRuntime.configureConsistencyMode(mode);
        int tagCount = 12;
        TagSet tagSet = tagSetRepository.save(new TagSet(unique("mode-set")));
        provider.reset();

        List<String> expectedSlugs = new ArrayList<>();
        for (int i = 0; i < tagCount; i++) {
            String displayName = unique("Mode Tag");
            expectedSlugs.add(displayName.toLowerCase().replace(' ', '-'));
            tagRepository.save(new Tag(tagSet, "en", displayName));
        }
        // Under EVENTUAL/COALESCED a stale read returns immediately and recomputes off-thread, so the
        // ledger is not complete the instant the loop ends.
        provider.ledger().awaitQuiescence();

        provider.ledger().assertEmbeddedExactlyOnce(expectedSlugs);
    }
}
