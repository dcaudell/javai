package dev.xtrafe.javai.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorType;
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

import java.util.UUID;

/**
 * Two {@code @Any} fields whose names <b>overlap</b>: {@code cover} is a suffix of {@code altCover}, so
 * {@code findByAltCoverOfType} also ends in {@code CoverOfType}.
 *
 * <p>That is not a contrived shape -- it is what caught the defect. {@link TestAlbum} declares exactly one
 * {@code @Any}, so nothing could shadow anything, and the first-match rewrite passed every test in this
 * module. The e2e domain's {@code AssocHub} has four, including {@code lazyAny} and {@code summaryLazyAny},
 * and stripping on behalf of the shorter one made a perfectly unambiguous method report itself as ambiguous.
 * Reproduced here so it stays fixed without needing the whole e2e container to notice.
 */
@Entity
final class TestPoster {

    @Id
    private UUID id;

    private String name;

    @Cascade({CascadeType.PERSIST, CascadeType.MERGE})
    @Any(fetch = FetchType.EAGER)
    @AnyDiscriminator(DiscriminatorType.STRING)
    @AnyDiscriminatorValue(discriminator = "image", entity = TestImageCover.class)
    @AnyDiscriminatorValue(discriminator = "lottie", entity = TestLottieCover.class)
    @AnyKeyJavaClass(UUID.class)
    @Column(name = "cover_type")
    @JoinColumn(name = "cover_id")
    private TestCover cover;

    @Cascade({CascadeType.PERSIST, CascadeType.MERGE})
    @Any(fetch = FetchType.EAGER)
    @AnyDiscriminator(DiscriminatorType.STRING)
    @AnyDiscriminatorValue(discriminator = "image", entity = TestImageCover.class)
    @AnyDiscriminatorValue(discriminator = "lottie", entity = TestLottieCover.class)
    @AnyKeyJavaClass(UUID.class)
    @Column(name = "alt_cover_type")
    @JoinColumn(name = "alt_cover_id")
    private TestCover altCover;

    TestPoster() {
    }

    TestPoster(String name, TestCover cover, TestCover altCover) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.cover = cover;
        this.altCover = altCover;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    TestCover getCover() {
        return cover;
    }

    TestCover getAltCover() {
        return altCover;
    }
}
