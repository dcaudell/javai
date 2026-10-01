package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.UUID;

/**
 * {@link PhotoAlbum}'s shape with the opt-in left off -- the unindexed half of OMI-458.
 *
 * <p>Its pixel summary is exactly as real and exactly as computable; nothing stores it. So
 * {@code nearestBySummary(PIXEL_MODEL)} against this type must return the same ranking the album's indexed
 * search returns, by folding every candidate in memory -- not the empty list an unprovisioned table would
 * give back, which reads as "nothing is similar" rather than "nothing is stored".
 */
@Entity
@JavAIVectorizable

public class PhotoCollage {

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @OneToMany(cascade = CascadeType.ALL)
    @Summary
    private JavAIList<PhotoAsset> photos = new JavAIArrayList<>();

    public PhotoCollage() {
    }

    public PhotoCollage(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public JavAIList<PhotoAsset> getPhotos() {
        return photos;
    }
}
