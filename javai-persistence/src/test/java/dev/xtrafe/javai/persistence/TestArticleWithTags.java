package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.runtime.DirtyTrackingSupport;
import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIList;
import dev.xtrafe.javai.runtime.JavAIRuntime;
import dev.xtrafe.javai.runtime.JavAIVectorizable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Neo4j-only fixture proving a {@code Map<String, TestTag>} relationship field round-trips correctly --
 * see {@link Neo4jRepositoryBackendTest#mapFieldRoundTripsWithKeysPreserved()}. Deliberately a separate
 * entity from {@link TestArticle} (not a map field added to it): {@code TestArticle} is shared with
 * {@link HibernatePostgresRepositoryBackendTest}, and this class exists purely to exercise
 * {@code Neo4jRepositoryBackend}'s own map-key persistence, which has no Postgres equivalent to keep in
 * sync with here.
 */
@Entity
final class TestArticleWithTags implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String title;

    private final Map<String, TestTag> tagsByCode = new LinkedHashMap<>();

    TestArticleWithTags() {
    }

    TestArticleWithTags(String title) {
        this.title = title;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    Map<String, TestTag> getTagsByCode() {
        return tagsByCode;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "title");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "title");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "title", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "title", reference);
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
