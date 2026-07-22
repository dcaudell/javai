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
 * A singular related entity that is <em>both</em> {@code @Entity} (so the Postgres backend maps it as an
 * ordinary Hibernate {@code @OneToOne} association {@link TestAccount} can filter through) and
 * {@code JavAIVectorizable} (so the Neo4j backend classifies {@code TestAccount.profile} as a relationship,
 * per its declared-type rule). That dual nature is exactly what lets one fixture exercise nested-association
 * derived finders ({@code findByProfileHandle}) on both backends -- see OMI-138. {@code handle} is a
 * {@code @Vectorize} field but is also stored as a plain, filterable column/property; {@code city} is a
 * plain scalar. Same hand-written-stand-in-for-woven-code pattern as {@link TestArticle}/{@link TestTag}.
 */
@Entity
final class TestProfile implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String handle;

    private String city;

    TestProfile() {
    }

    TestProfile(String handle, String city) {
        this.handle = handle;
        this.city = city;
    }

    UUID getId() {
        return id;
    }

    String getHandle() {
        return handle;
    }

    String getCity() {
        return city;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "handle");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "handle");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "handle");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "handle", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "handle", reference);
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
