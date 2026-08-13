package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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
     *  {@link JavAITagRepository#classify} needs to diff a fresh classification run against what's already there
     *  without disturbing manually-applied tags. */
    List<TagAssociation> associationsOf(TaggableRef ref);

    /** Every {@link TaggableRef} tagged with {@code tagId}, restricted to instances of one of
     *  {@code candidateTypeNames} (each a fully-qualified class name, matching {@link TaggableRef#taggableType()}'s
     *  own convention). */
    List<TaggableRef> taggedWith(UUID tagId, List<String> candidateTypeNames);

    /** Writes/overwrites {@code ref}'s tag-summary vector -- called by {@link JavAITagRepository} after every
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

    // ---- Taggregate (OMI-302) -- membership snapshot, pending set, batched/aggregate queries, tag text ----

    /**
     * Every association of every ref in {@code refs}, in <b>one batched query</b> -- the recompute cost
     * discipline doc/spec/tagging.md's "Taggregate" section demands (never per-member lookups). Every
     * requested ref appears as a key, mapped to an empty list when it has no taggings. MongoDB's
     * realization is one query per distinct {@code taggableType} in {@code refs} (associations live
     * embedded on each type's own collection) -- a documented correct-everywhere deviation, the
     * {@code findPendingVector} precedent.
     */
    Map<TaggableRef, List<TagAssociation>> associationsOfAll(Collection<TaggableRef> refs);

    /**
     * Replaces {@code aggregate}'s membership snapshot wholesale ({@code javai_taggregate_members}) --
     * written by recompute, never by interception, per doc/spec/tagging.md's "Staleness". Wholesale
     * replacement is what makes concurrent reconciles of one aggregate converge: last write wins with no
     * partial merge.
     */
    void replaceTaggregateMembers(TaggableRef aggregate, List<TaggableRef> members);

    /** {@code aggregate}'s membership snapshot as of its last recompute -- what drift detection compares
     *  the current reflective field walk against. Empty for a never-reconciled aggregate. */
    List<TaggableRef> taggregateMembers(TaggableRef aggregate);

    /** Every aggregate whose snapshot lists {@code member} -- the one indexed lookup the mutation choke
     *  points use to find who to mark pending. */
    List<TaggableRef> taggregatesContaining(TaggableRef member);

    /**
     * Records that {@code aggregate} owes a recompute ({@code javai_taggregate_pending}) -- insert-only,
     * duplicates expected and collapsed at claim time, exactly the {@code javai_summary_pending} shape
     * (see {@code javai-persistence}'s {@code PendingSummaries} for why enqueue must never read first).
     */
    void enqueueTaggregatePending(TaggableRef aggregate);

    /** Claims up to {@code limit} distinct pending aggregates, oldest first. Reads only -- rows stay until
     *  {@link #deleteTaggregatePending} confirms their work is done, so a sweep that dies loses nothing. */
    TaggregatePendingClaim claimTaggregatePending(int limit);

    /** Claims only the rows naming {@code aggregate} -- what a read holding the object drains when it
     *  reconciles lazily, without taking on every other aggregate's backlog. */
    TaggregatePendingClaim claimTaggregatePendingFor(TaggableRef aggregate);

    /** Removes exactly the rows a completed reconcile claimed, by id, never by aggregate -- a row enqueued
     *  after the claim describes a mutation the finished recompute may not have seen, and must survive. */
    void deleteTaggregatePending(List<UUID> rowIds);

    /**
     * Every ref (of one of {@code candidateTypeNames}) carrying at least one of {@code tagIds}, scored
     * {@code Σ COALESCE(affinity, 1.0)} over the query tags it carries, ordered score-descending (ties by
     * ref, so results are deterministic), capped at {@code limit}. One indexed query over the taggings --
     * exact and explainable, the structural counterpart to {@link #nearestByTagSummaryVector}. MongoDB runs
     * one aggregation per candidate type and merges -- same documented deviation as
     * {@link #associationsOfAll}.
     */
    List<RankedTaggableRef> rankedByTags(List<UUID> tagIds, List<String> candidateTypeNames, int limit);

    /** Writes/overwrites {@code ref}'s tag-text vector and the exact text it embeds, per ref per model
     *  ({@code javai_tag_text_vectors__<model>}) -- see doc/spec/tagging.md's "Concatenated tag text". */
    void upsertTagTextVector(TaggableRef ref, String text, EmbeddingVector vector);

    /** Removes {@code ref} from the tag-text index entirely, every model -- called when {@code ref} has no
     *  taggings left (or its type no longer opts in), mirroring {@link #deleteTagSummaryVector}. */
    void deleteTagTextVector(TaggableRef ref);

    /** The stored tag text for {@code ref} under {@code modelId}, or {@code null} if none is stored. The
     *  text is deterministic and model-independent by construction; it is stored per model only because it
     *  lives beside the vector it was embedded into. */
    String tagText(TaggableRef ref, String modelId);

    /** The stored tag-text vector for {@code ref} under {@code modelId}, or {@code EmbeddingVector.absent()}
     *  if none is stored -- what backs {@code JavAITagRepository#tagTextVector(Object)}. */
    EmbeddingVector tagTextVector(TaggableRef ref, String modelId);

    /** The {@code n} refs whose tag-text vector is most similar to {@code reference} --
     *  {@code reference.modelId()} selects the model realization, mirroring {@link #nearestByTagSummaryVector}. */
    List<RankedTaggableRef> nearestByTagTextVector(EmbeddingVector reference, int n);

    /** Distinct refs currently in the tag-text index, across every model -- backs the tag-text
     *  {@code VectorIndex}'s {@code size()}/{@code filterByMinSimilarity}, mirroring {@link #tagSummaryVectorCount}. */
    int tagTextVectorCount();
}
