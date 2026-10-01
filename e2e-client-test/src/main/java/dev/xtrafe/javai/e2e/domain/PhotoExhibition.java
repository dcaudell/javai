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
 * ⚠️ <b>Summaries of summaries</b> (OMI-458): a container of {@link PhotoAlbum}s, which are themselves
 * containers of {@link PhotoAsset}s.
 *
 * <p>Nothing on this exhibition is in the pixel model, and nothing on an album is either -- only the leaves
 * carry a supplied vector, two hops down. So its pixel summary exists only if three independent things are
 * each transitive: {@code summaryVector(modelId)} folding through a tier whose own contribution is absent,
 * the model-discovery walk reaching a declaration two hops below the flag, and the summary drain walking two
 * hops back up when a leaf's vector finally arrives. A one-tier fixture passes while all three are broken.
 */
@Entity
@JavAIVectorizable
@Summary(persistModelSummaries = true)
public class PhotoExhibition {

    @Id
    private UUID id;

    @Vectorize
    private String name;

    @OneToMany(cascade = CascadeType.ALL)
    @Summary
    private JavAIList<PhotoAlbum> albums = new JavAIArrayList<>();

    public PhotoExhibition() {
    }

    public PhotoExhibition(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public JavAIList<PhotoAlbum> getAlbums() {
        return albums;
    }
}
