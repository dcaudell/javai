package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * Minimal related-entity fixture for {@link TestArticleWithTags}' {@code Map<String, TestTag>} field --
 * same hand-written-stand-in-for-woven-code pattern as {@link TestArticle}, kept separate from it (rather
 * than adding a map field to {@code TestArticle} itself) so this Neo4j-specific map-key round-trip test
 * can't affect {@link HibernatePostgresRepositoryBackendTest}, which reuses {@code TestArticle} too.
 */
@Entity
final class TestTag implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String label;

    TestTag() {
    }

    TestTag(String label) {
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "label");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "label");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "label", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "label", reference);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type) {
        return JavAIRuntime.query(this, reference, type, Integer.MAX_VALUE);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth) {
        return JavAIRuntime.query(this, reference, type, maxDepth);
    }

    @Override
    public EmbeddingVector fieldVector(String fieldName) {
        return JavAIRuntime.fieldVector(this, fieldName);
    }
}
