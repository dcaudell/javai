package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * OMI-230's narrowed-vector-search fixture, shaped like the {@code MediaSocialDetails} the ticket was
 * reported against: one vectorized description shared by several media kinds, where the useful query is
 * "nearest to this text, <em>and</em> of this kind."
 *
 * <p>The scalar fields exist to be narrowed by, and are chosen to cover the operator families separately
 * rather than redundantly: an enum for {@code Is}/{@code In}, a boolean for {@code True}/{@code False}, an
 * int for the comparison and {@code Between} operators, and a deliberately nullable string for
 * {@code IsNull}/{@code IsNotNull} and the string-matching operators.
 *
 * <p>Same hand-written {@code JavAIVectorizable} stand-in pattern as every other fixture in this module --
 * see {@link TestArticle}'s javadoc for why there is no weaver here.
 */
@Entity
final class TestAsset implements JavAIVectorizable {

    enum Kind { IMAGE, SHORT, AUDIO }

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String caption;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private boolean published;

    private int rating;

    /** Nullable on purpose -- the only field here that can exercise IsNull/IsNotNull. */
    private String owner;

    TestAsset() {
    }

    TestAsset(String caption, Kind kind, boolean published, int rating, String owner) {
        this.id = UUID.randomUUID();
        this.caption = caption;
        this.kind = kind;
        this.published = published;
        this.rating = rating;
        this.owner = owner;
    }

    UUID getId() {
        return id;
    }

    String getCaption() {
        return caption;
    }

    Kind getKind() {
        return kind;
    }

    boolean isPublished() {
        return published;
    }

    int getRating() {
        return rating;
    }

    String getOwner() {
        return owner;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "caption");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "caption");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "caption");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "caption", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "caption", reference);
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
