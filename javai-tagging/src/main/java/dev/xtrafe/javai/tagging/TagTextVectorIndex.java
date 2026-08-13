package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.collections.VectorIndex;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.vector.EmbeddingVector;

/**
 * The {@code VectorIndex<TaggableRef>} {@link JavAITagRepository#tagTextIndex()} returns -- the tag-text
 * sibling of {@link TagSimilarityVectorIndex}, identical posture: persistence-backed, maintained
 * automatically at the repository's own trigger points, so the {@code VectorIndex} contract's
 * {@link #add}/{@link #remove} refuse.
 */
final class TagTextVectorIndex implements VectorIndex<TaggableRef> {

    private final TaggingBackend backend;

    TagTextVectorIndex(TaggingBackend backend) {
        this.backend = backend;
    }

    @Override
    public void add(TaggableRef item) {
        throw new UnsupportedOperationException(
                "tagTextIndex() is maintained automatically by JavAITagRepository's own mutation points -- "
                        + "see doc/spec/tagging.md's Concatenated tag text");
    }

    @Override
    public boolean remove(TaggableRef item) {
        throw new UnsupportedOperationException(
                "tagTextIndex() is maintained automatically by JavAITagRepository's own mutation points -- "
                        + "see doc/spec/tagging.md's Concatenated tag text");
    }

    @Override
    public int size() {
        return backend.tagTextVectorCount();
    }

    @Override
    public JavAIList<TaggableRef> nearestN(EmbeddingVector reference, int n) {
        JavAIArrayList<TaggableRef> results = new JavAIArrayList<>();
        for (RankedTaggableRef ranked : backend.nearestByTagTextVector(reference, n)) {
            results.add(ranked.ref());
        }
        return results;
    }

    @Override
    public JavAIList<TaggableRef> filterByMinSimilarity(EmbeddingVector reference, double threshold) {
        int everything = Math.max(backend.tagTextVectorCount(), 1);
        JavAIArrayList<TaggableRef> results = new JavAIArrayList<>();
        for (RankedTaggableRef ranked : backend.nearestByTagTextVector(reference, everything)) {
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
