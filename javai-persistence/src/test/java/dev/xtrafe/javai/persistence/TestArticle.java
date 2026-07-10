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
 * Hand-written stand-in for what a woven {@code @JavAIVectorizable} class would look like -- same pattern
 * as {@code javai-collections}'/{@code javai-substrate}'s own test fixtures: this module has no dependency on
 * {@code javai-substrate}, so there's no weaver to run in these tests, matching the module dependency graph
 * (Persistence Bridge depends on Vector Core + Vector Collections, not the Acceleration Substrate
 * directly). Delegates everything to {@link JavAIRuntime}, exactly like real woven bytecode would --
 * including the {@code transient $javai$state} field (the JVM modifier, not just an annotation), so
 * Hibernate's default field-access mapping skips it automatically when this doubles as a JPA
 * {@code @Entity} for the Postgres backend's tests. Reused as-is for the Neo4j backend's tests too: the
 * {@code @Entity} annotation is simply ignored there.
 */
@Entity
final class TestArticle implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @Vectorize
    private String body;

    TestArticle() {
    }

    TestArticle(String title, String body) {
        this.title = title;
        this.body = body;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    void setTitle(String title) {
        this.title = title;
        JavAIRuntime.markFieldDirty(this);
        JavAIRuntime.propagateDirty(this);
    }

    String getBody() {
        return body;
    }

    void setBody(String body) {
        this.body = body;
        JavAIRuntime.markFieldDirty(this);
        JavAIRuntime.propagateDirty(this);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "title,body");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "title,body");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "title,body", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "title,body", reference);
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
