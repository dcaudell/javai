package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import org.hibernate.annotations.Any;
import org.hibernate.annotations.AnyDiscriminator;
import org.hibernate.annotations.AnyDiscriminatorValue;
import org.hibernate.annotations.AnyKeyJavaClass;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;
import jakarta.persistence.DiscriminatorType;

import java.util.UUID;

/**
 * OMI-212's reproduction, mirroring the shape that surfaced it in {@code omiai-platform}: an entity holding
 * one polymorphic reference that may point at any of several unrelated entities.
 *
 * <p>Both {@code @Any} targets ({@link TestImageCover}, {@link TestLottieCover}) are reachable <em>only</em>
 * through {@code @AnyDiscriminatorValue}. Nothing else in this fixture mentions them, and neither is a
 * declared field type anywhere -- which is exactly the condition under which JavAI's field-walking discovery
 * never registered them, and the {@code SessionFactory} then failed to boot with
 * {@code UnknownEntityTypeException}. Adding any other reference to them here would silently defeat the
 * test.
 *
 * <p>Vectorizable as well, so the fix is exercised on the path JavAI actually cares about rather than on a
 * plain JPA entity that happens to compile.
 */
@Entity
final class TestAlbum implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String title;

    // Cascaded so the album owns its cover, which is what lets this fixture keep the two target types
    // reachable ONLY through the discriminator. @Any does not cascade by default, so without this the test
    // would have to save each cover through its own repository -- registering those types by the ordinary
    // field walk and quietly defeating the very thing being tested.
    @Cascade({CascadeType.PERSIST, CascadeType.MERGE})
    @Any(fetch = FetchType.EAGER)
    @AnyDiscriminator(DiscriminatorType.STRING)
    @AnyDiscriminatorValue(discriminator = "image", entity = TestImageCover.class)
    @AnyDiscriminatorValue(discriminator = "lottie", entity = TestLottieCover.class)
    @AnyKeyJavaClass(UUID.class)
    @Column(name = "cover_type")
    @JoinColumn(name = "cover_id")
    private TestCover cover;

    TestAlbum() {
    }

    TestAlbum(String title, TestCover cover) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.cover = cover;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    TestCover getCover() {
        return cover;
    }

    void setCover(TestCover cover) {
        this.cover = cover;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "title");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "title");
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
