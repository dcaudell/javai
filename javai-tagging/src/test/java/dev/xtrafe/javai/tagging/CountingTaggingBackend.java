package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pass-through decorator that counts calls per {@link TaggingBackend} method -- what turns OMI-302's
 * "recompute reads member taggings in <b>one</b> batched query" from a vibe into an assertion. Injected via
 * {@code JavAITagRepository}'s package-private test constructor.
 */
final class CountingTaggingBackend implements TaggingBackend {

    private final TaggingBackend delegate;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    CountingTaggingBackend(TaggingBackend delegate) {
        this.delegate = delegate;
    }

    int callsTo(String method) {
        AtomicInteger count = calls.get(method);
        return count == null ? 0 : count.get();
    }

    void reset() {
        calls.clear();
    }

    private void count(String method) {
        calls.computeIfAbsent(method, ignored -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void addTag(TaggableRef ref, UUID tagId, Double affinity, String source) {
        count("addTag");
        delegate.addTag(ref, tagId, affinity, source);
    }

    @Override
    public void removeTag(TaggableRef ref, UUID tagId) {
        count("removeTag");
        delegate.removeTag(ref, tagId);
    }

    @Override
    public boolean hasTag(TaggableRef ref, UUID tagId) {
        count("hasTag");
        return delegate.hasTag(ref, tagId);
    }

    @Override
    public List<UUID> tagIdsOf(TaggableRef ref) {
        count("tagIdsOf");
        return delegate.tagIdsOf(ref);
    }

    @Override
    public List<TagAssociation> associationsOf(TaggableRef ref) {
        count("associationsOf");
        return delegate.associationsOf(ref);
    }

    @Override
    public List<TaggableRef> taggedWith(UUID tagId, List<String> candidateTypeNames) {
        count("taggedWith");
        return delegate.taggedWith(tagId, candidateTypeNames);
    }

    @Override
    public void upsertTagSummaryVector(TaggableRef ref, EmbeddingVector vector) {
        count("upsertTagSummaryVector");
        delegate.upsertTagSummaryVector(ref, vector);
    }

    @Override
    public void deleteTagSummaryVector(TaggableRef ref) {
        count("deleteTagSummaryVector");
        delegate.deleteTagSummaryVector(ref);
    }

    @Override
    public List<RankedTaggableRef> nearestByTagSummaryVector(EmbeddingVector reference, int n) {
        count("nearestByTagSummaryVector");
        return delegate.nearestByTagSummaryVector(reference, n);
    }

    @Override
    public int tagSummaryVectorCount() {
        count("tagSummaryVectorCount");
        return delegate.tagSummaryVectorCount();
    }

    @Override
    public Map<TaggableRef, List<TagAssociation>> associationsOfAll(Collection<TaggableRef> refs) {
        count("associationsOfAll");
        return delegate.associationsOfAll(refs);
    }




    @Override
    public void enqueueTaggregatePending(TaggableRef aggregate) {
        count("enqueueTaggregatePending");
        delegate.enqueueTaggregatePending(aggregate);
    }

    @Override
    public TaggregatePendingClaim claimTaggregatePending(int limit) {
        count("claimTaggregatePending");
        return delegate.claimTaggregatePending(limit);
    }

    @Override
    public TaggregatePendingClaim claimTaggregatePendingFor(TaggableRef aggregate) {
        count("claimTaggregatePendingFor");
        return delegate.claimTaggregatePendingFor(aggregate);
    }

    @Override
    public void deleteTaggregatePending(List<UUID> rowIds) {
        count("deleteTaggregatePending");
        delegate.deleteTaggregatePending(rowIds);
    }

    @Override
    public List<RankedTaggableRef> rankedByTags(List<UUID> tagIds, List<String> candidateTypeNames, int limit) {
        count("rankedByTags");
        return delegate.rankedByTags(tagIds, candidateTypeNames, limit);
    }

    @Override
    public void upsertTagTextVector(TaggableRef ref, String text, EmbeddingVector vector) {
        count("upsertTagTextVector");
        delegate.upsertTagTextVector(ref, text, vector);
    }

    @Override
    public void deleteTagTextVector(TaggableRef ref) {
        count("deleteTagTextVector");
        delegate.deleteTagTextVector(ref);
    }

    @Override
    public String tagText(TaggableRef ref, String modelId) {
        count("tagText");
        return delegate.tagText(ref, modelId);
    }

    @Override
    public EmbeddingVector tagTextVector(TaggableRef ref, String modelId) {
        count("tagTextVector");
        return delegate.tagTextVector(ref, modelId);
    }

    @Override
    public List<RankedTaggableRef> nearestByTagTextVector(EmbeddingVector reference, int n) {
        count("nearestByTagTextVector");
        return delegate.nearestByTagTextVector(reference, n);
    }

    @Override
    public int tagTextVectorCount() {
        count("tagTextVectorCount");
        return delegate.tagTextVectorCount();
    }
}
