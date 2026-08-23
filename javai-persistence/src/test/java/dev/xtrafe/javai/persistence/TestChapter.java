package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Summary;
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
 * A chapter that both contributes its own text and absorbs its child's -- the fixture for concatenated text
 * vectoring's persistence (OMI-191).
 *
 * <p>Separate from {@link TestArticle} deliberately: {@code TestArticle} does <em>not</em> opt in, which
 * makes it the control for "declining stores nothing", and having one fixture answer both questions would
 * cost that.
 *
 * <p>Same hand-written stand-in pattern as every other fixture in this module -- see {@link TestArticle}'s
 * javadoc for why there is no weaver here.
 */
@Entity
@Summary(concatenate = true)
final class TestChapter implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String heading;

    @Vectorize
    private String prose;

    /**
     * Absorbs this chapter's continuation, so assembly is provably recursive rather than one level deep.
     *
     * <p>{@code @OneToOne} with a cascade because Postgres maps this through real Hibernate; the other two
     * backends classify a relationship by declared type and ignore the annotation entirely.
     */
    @Summary(concatenate = true)
    @jakarta.persistence.OneToOne(cascade = jakarta.persistence.CascadeType.ALL)
    private TestChapter continuation;

    TestChapter() {
    }

    TestChapter(String heading, String prose) {
        this.heading = heading;
        this.prose = prose;
    }

    UUID getId() {
        return id;
    }

    String getHeading() {
        return heading;
    }

    void setHeading(String heading) {
        String oldValue = this.heading;
        this.heading = heading;
        JavAIRuntime.vectorizeFieldMutated(this, "heading", oldValue, heading);
    }

    void setProse(String prose) {
        String oldValue = this.prose;
        this.prose = prose;
        JavAIRuntime.vectorizeFieldMutated(this, "prose", oldValue, prose);
    }

    TestChapter getContinuation() {
        return continuation;
    }

    void setContinuation(TestChapter continuation) {
        this.continuation = continuation;
        JavAIRuntime.registerDependency(this, continuation);
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "heading,prose");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "heading,prose");
    }

    @Override
    public String concatenatedText() {
        return JavAIRuntime.concatenatedText(this, "heading,prose");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "continuation", "heading,prose");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "heading,prose", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "heading,prose", reference);
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
