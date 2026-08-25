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
 * A container that opts into persisted per-model summaries (OMI-458) -- the middle tier.
 *
 * <p>{@code @Summary(persistModelSummaries = true)} at the type level is the entire opt-in, and it is what
 * turns "rank albums by what their pictures look like" from a fold over every candidate into an indexed
 * lookup against {@code javai_summary_vectors__e2e_fake_pixels_pp1}. Which model that is was never
 * configured: it is derived from the {@code @ExternalVector} its members declare.
 *
 * <p>{@link PhotoCollage} is deliberately the same shape without the flag, so the two paths can be compared
 * on the same data.
 */
@Entity
@JavAIVectorizable
@Summary(persistModelSummaries = true)
public class PhotoAlbum {

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @OneToMany(cascade = CascadeType.ALL)
    @Summary
    private JavAIList<PhotoAsset> photos = new JavAIArrayList<>();

    public PhotoAlbum() {
    }

    public PhotoAlbum(String title) {
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
