package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.vector.EmbeddingVector;

/**
 * The {@code VectorIndex<TaggableRef>} {@link JavAITagRepository#tagSimilarityIndex()} returns -- see
 * doc/spec/tagging.md's "Tag-similarity search". Deliberately read-mostly: unlike {@code JavAIVectorIndex}
 * (a plain, caller-populated in-memory container), this realization is persistence-backed and maintained
 * automatically as a side effect of {@link JavAITagRepository#addTag}/{@link JavAITagRepository#removeTag},
 * so {@link #add}/{@link #remove} -- required by the {@link VectorIndex} contract, but with no legitimate
 * caller here -- simply refuse.
 */
final class TagSimilarityVectorIndex implements VectorIndex<TaggableRef> {

    private final TaggingBackend backend;

    TagSimilarityVectorIndex(TaggingBackend backend) {
        this.backend = backend;
    }

    @Override
    public void add(TaggableRef item) {
        throw new UnsupportedOperationException(
                "tagSimilarityIndex() is maintained automatically by JavAITagRepository.addTag()/removeTag() -- "
                        + "see doc/spec/tagging.md's Tag-summary vector index");
    }

    @Override
    public boolean remove(TaggableRef item) {
        throw new UnsupportedOperationException(
                "tagSimilarityIndex() is maintained automatically by JavAITagRepository.addTag()/removeTag() -- "
                        + "see doc/spec/tagging.md's Tag-summary vector index");
    }

    @Override
    public int size() {
        return backend.tagSummaryVectorCount();
    }

    @Override
    public JavAIList<TaggableRef> nearestN(EmbeddingVector reference, int n) {
        JavAIArrayList<TaggableRef> results = new JavAIArrayList<>();
        for (RankedTaggableRef ranked : backend.nearestByTagSummaryVector(reference, n)) {
            results.add(ranked.ref());
        }
        return results;
    }

    @Override
    public JavAIList<TaggableRef> filterByMinSimilarity(EmbeddingVector reference, double threshold) {
        int everything = Math.max(backend.tagSummaryVectorCount(), 1);
        JavAIArrayList<TaggableRef> results = new JavAIArrayList<>();
        for (RankedTaggableRef ranked : backend.nearestByTagSummaryVector(reference, everything)) {
            if (ranked.similarity() >= threshold) {
                results.add(ranked.ref());
            }
        }
        return results;
    }

    @Override
    public JavAIList<TaggableRef> sortByCosineDistance(EmbeddingVector reference) {
        return nearestN(reference, Math.max(size(), 1));
    }
}
