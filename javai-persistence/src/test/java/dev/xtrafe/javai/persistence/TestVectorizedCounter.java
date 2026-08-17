package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * A vectorized entity that also carries plain counters -- the mix OMI-406's refusal has to tell apart.
 *
 * <p>{@code headline} is {@code @Vectorize}, so a bulk update assigning to it is refused: nothing would
 * re-embed it, and the stale vector is stored and hydrated back on every later load. {@code tally} and
 * {@code protectedTally} are ordinary columns on that same entity, and must stay writable through a targeted
 * query -- a summary is arithmetic over vectors, so a column no vector reads cannot move one. Getting only
 * the first half right would be a refusal that blocks the feature; getting only the second right would be
 * the durable inconsistency {@code SPEC.md} warns about.
 *
 * <p>Hand-written like {@link TestArticle}, for the same reason: no weaver runs in this module's tests.
 */
@Entity
final class TestVectorizedCounter implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String headline;

    private long tally;

    @Column(updatable = false)
    private long protectedTally;

    TestVectorizedCounter() {
    }

    TestVectorizedCounter(String headline, long tally, long protectedTally) {
        this.id = UUID.randomUUID();
        this.headline = headline;
        this.tally = tally;
        this.protectedTally = protectedTally;
    }

    UUID getId() {
        return id;
    }

    String getHeadline() {
        return headline;
    }

    void setHeadline(String headline) {
        String oldValue = this.headline;
        this.headline = headline;
        JavAIRuntime.vectorizeFieldMutated(this, "headline", oldValue, headline);
    }

    long getTally() {
        return tally;
    }

    void setTally(long tally) {
        this.tally = tally;
    }

    long getProtectedTally() {
        return protectedTally;
    }

    void setProtectedTally(long protectedTally) {
        this.protectedTally = protectedTally;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "headline");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "headline");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "headline");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "headline", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "headline", reference);
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
