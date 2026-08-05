package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * The shape OMI-230 was reported against, as a real woven client entity: one vectorized piece of text
 * shared across several media kinds, where the query anyone actually wants is "nearest to this description,
 * <em>of this kind</em>."
 *
 * <p>Deliberately a new entity rather than a scalar bolted onto {@link Article}. {@code Article} is the
 * shared fixture half this module's suites assert against, and giving it a new column would ripple through
 * their seeded data and expectations for no benefit -- while this entity can be laid out precisely for the
 * one property that matters here: the kind whose members are nearest must be the kind the query does
 * <em>not</em> ask for, so a narrowed search cannot be satisfied by filtering an already-ranked page.
 *
 * <p>Woven at runtime like every other entity in this project ({@code @JavAIVectorizable}, no hand-written
 * interface implementation), so the narrowing feature is exercised against a genuinely woven class rather
 * than a hand-rolled stand-in.
 */
@Entity
@JavAIVectorizable
public class MediaNote {

    public enum Kind { AUDIO, VIDEO }

    @Id
    private UUID id;

    @Vectorize
    private String caption;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private boolean published;

    /** Reflective-hydration only -- application code should use the real constructor. */
    public MediaNote() {
    }

    public MediaNote(String caption, Kind kind, boolean published) {
        this.id = UUID.randomUUID();
        this.caption = caption;
        this.kind = kind;
        this.published = published;
    }

    public UUID getId() {
        return id;
    }

    public String getCaption() {
        return caption;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isPublished() {
        return published;
    }
}
