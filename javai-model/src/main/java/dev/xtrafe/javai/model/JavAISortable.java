package dev.xtrafe.javai.model;

import dev.xtrafe.javai.vector.EmbeddingVector;

/**
 * One cosine-distance sorting contract, shared by every JavAI collection -- JavAIList,
 * JavAISet, VectorIndex, and KnowledgeGraph's node view all implement this, not four
 * different sort methods. See doc/spec/vector-collections.md.
 *
 * <p>Lives in javai-model, not javai-collections, alongside JavAIVectorizable and
 * JavAIList/Set/Map -- see the module-placement note in package-info.java.
 *
 * @param <T> the element type being ranked
 */
public interface JavAISortable<T> {

    /** Ascending distance = descending similarity. */
    JavAIList<T> sortByCosineDistance(EmbeddingVector reference);
}
