package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;
import java.util.UUID;

/**
 * The persisted-association contract each backend realizes for {@link Tagging} rows -- deliberately
 * separate from {@code javai-persistence}'s own {@code RepositoryBackend}: Tag/TagSet persistence already
 * goes through that existing mechanism unchanged (a plain {@code JavAIRepository<Tag>}, see {@link Tag}'s
 * own javadoc), so this interface only ever needs to cover the association itself, which has no equivalent
 * in {@code JavAIRepository}'s CRUD contract. See doc/spec/tagging.md's "Persistence, across all three
 * backends" for each implementation's actual storage shape.
 *
 * <p>{@code addTag} is idempotent per {@code (ref, tagId)} -- re-adding the same tag to the same instance
 * updates {@code affinity}/{@code source} rather than creating a second row/relationship/array entry,
 * which is what actually enforces "a given Tag has zero or one association to a given instance" (see
 * doc/spec/tagging.md's "Uniqueness").
 */
interface TaggingBackend {

    void addTag(TaggableRef ref, UUID tagId, Double affinity, String source);

    void removeTag(TaggableRef ref, UUID tagId);

    boolean hasTag(TaggableRef ref, UUID tagId);

    List<UUID> tagIdsOf(TaggableRef ref);

    /** Like {@link #tagIdsOf}, but with each association's {@code affinity}/{@code source} too -- what
     *  {@link JavAITagging#classify} needs to diff a fresh classification run against what's already there
     *  without disturbing manually-applied tags. */
    List<TagAssociation> associationsOf(TaggableRef ref);

    /** Every {@link TaggableRef} tagged with {@code tagId}, restricted to instances of one of
     *  {@code candidateTypeNames} (each a fully-qualified class name, matching {@link TaggableRef#taggableType()}'s
     *  own convention). */
    List<TaggableRef> taggedWith(UUID tagId, List<String> candidateTypeNames);

    /** Writes/overwrites {@code ref}'s tag-summary vector -- called by {@link JavAITagging} after every
     *  {@code addTag}/{@code removeTag}, per doc/spec/tagging.md's "recomputed eagerly, not lazily" rule.
     *  Never called directly by a caller of this module's public surface. */
    void upsertTagSummaryVector(TaggableRef ref, EmbeddingVector vector);

    /** Removes {@code ref} from the tag-summary-vector index entirely -- called when {@code ref} has zero
     *  remaining Taggings, regardless of which embedding model(s) it was previously indexed under. */
    void deleteTagSummaryVector(TaggableRef ref);

    /** The {@code n} {@link TaggableRef}s (of any type -- this index deliberately spans every {@code
     *  @Taggable} type at once) whose tag-summary vector is most similar to {@code reference}, each paired
     *  with its cosine similarity. {@code reference.modelId()} selects which model's realization of the
     *  index is queried. */
    List<RankedTaggableRef> nearestByTagSummaryVector(EmbeddingVector reference, int n);

    /** The number of distinct {@link TaggableRef}s currently in the tag-summary-vector index, across every
     *  model it's ever been realized under -- what backs {@link TagSimilarityVectorIndex#size()}, and the
     *  upper bound {@link TagSimilarityVectorIndex#filterByMinSimilarity} uses to fetch the whole index via
     *  {@link #nearestByTagSummaryVector} before applying its own threshold. */
    int tagSummaryVectorCount();
}
