package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

/**
 * A leaf carrying <b>both</b> kinds of vector at once: a {@code @Vectorize} caption the configured provider
 * really embeds, and an {@code @ExternalVector} in a model nothing in this process can compute (OMI-458).
 *
 * <p>⚠️ <b>Both, deliberately.</b> Carrying an external vector does not stop an entity carrying an ordinary
 * one, so a container of these has two coherent summaries -- what its pictures look like and what their
 * captions read like -- of different dimensionality, in models {@code VectorMath} refuses to combine. Every
 * claim about one has to hold without disturbing the other, and a fixture with only the external vector
 * could not catch a change that quietly conflated them.
 *
 * <p>Woven at runtime like every entity in this module: {@code @JavAIVectorizable} and no hand-written
 * interface implementation, so {@code pixelsVector()} and {@code summaryVector(String)} are the weaver's.
 */
@Entity
@JavAIVectorizable
@ExternalVector(name = "pixels", keyField = "contentHash", model = PhotoAsset.PIXEL_MODEL)
public class PhotoAsset {

    /** Every dimension of the model's identity folded into one string, as {@code @ExternalVector} requires:
     *  storage is partitioned by it, so a change here is an additive migration rather than an overwrite. */
    public static final String PIXEL_MODEL = "e2e-fake-pixels/pp1";

    public static final int PIXEL_DIMS = 8;

    @Id
    private UUID id;

    @Vectorize
    private String caption;

    /** Identifies the bytes the pixel vector describes. JavAI never reads them. */
    private String contentHash;

    /** Reflective-hydration only -- application code should use the real constructor. */
    public PhotoAsset() {
    }

    public PhotoAsset(String caption, String contentHash) {
        this.id = UUID.randomUUID();
        this.caption = caption;
        this.contentHash = contentHash;
    }

    /**
     * A deterministic stand-in for what an image embedder would return -- the point of the fixture is that
     * JavAI never computes this, so where the numbers come from is the test's business and not the
     * library's.
     */
    public static EmbeddingVector pixelVector(float seed) {
        float[] values = new float[PIXEL_DIMS];
        for (int i = 0; i < PIXEL_DIMS; i++) {
            values[i] = (float) Math.sin(seed * 7.0 + i);
        }
        return new EmbeddingVector(values, PIXEL_MODEL, PIXEL_DIMS, Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public String getCaption() {
        return caption;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
