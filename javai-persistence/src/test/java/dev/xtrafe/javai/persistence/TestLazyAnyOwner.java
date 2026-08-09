package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorType;

import org.hibernate.annotations.Any;
import org.hibernate.annotations.AnyDiscriminator;
import org.hibernate.annotations.AnyDiscriminatorValue;
import org.hibernate.annotations.AnyKeyJavaClass;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;

import java.util.UUID;

/**
 * {@code @Any(fetch = LAZY)} -- the fetch mode {@link TestAlbum} does not cover, since its own {@code cover}
 * is declared {@code EAGER}.
 *
 * <p>Worth its own fixture rather than an assumption that it mirrors a lazy {@code @ManyToOne}: an
 * {@code @Any} target is resolved through a <em>discriminator string plus a bare id</em>, not a foreign key,
 * so the concrete class is not known until the discriminator is read. Whether Hibernate can proxy something
 * whose type it has not resolved yet is exactly the question, and it is not answerable from the annotation.
 */
@Entity
final class TestLazyAnyOwner {

    @Id
    private UUID id;

    private String label;

    @Any(fetch = FetchType.LAZY)
    @AnyDiscriminator(DiscriminatorType.STRING)
    @AnyDiscriminatorValue(discriminator = "image", entity = TestImageCover.class)
    @AnyDiscriminatorValue(discriminator = "lottie", entity = TestLottieCover.class)
    @AnyKeyJavaClass(UUID.class)
    @Column(name = "cover_kind")
    @JoinColumn(name = "cover_id")
    @Cascade({ CascadeType.PERSIST, CascadeType.MERGE })
    private TestCover cover;

    TestLazyAnyOwner() {
    }

    TestLazyAnyOwner(String label, TestCover cover) {
        this.label = label;
        this.cover = cover;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    TestCover getCover() {
        return cover;
    }
}
