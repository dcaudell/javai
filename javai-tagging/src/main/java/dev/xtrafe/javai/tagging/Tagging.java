package dev.xtrafe.javai.tagging;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted association row -- Tag + taggable type/UUID + optional affinity + source. Not an
 * {@code @Entity}/{@code @JavAIVectorizable} class going through {@code JavAIRepository}: its persistence is
 * genuinely backend-specific (a Postgres join table row, a native Neo4j {@code TAGGED_WITH} relationship, a
 * MongoDB reference-pointer array entry -- see doc/spec/tagging.md's "Persistence, across all three
 * backends"), maintained internally by {@code JavAITagging}'s own per-backend code, not by a caller
 * constructing rows directly for storage. This type exists to give that row shape a name and to hand back
 * to a caller who wants the metadata ({@link #affinity()}, {@link #source()}, {@link #createdAt()}) rather
 * than just the bare {@link Tag}.
 *
 * <p>{@link #affinity()} lives here, not on {@link Tag} -- a tag's strength of match is a property of one
 * particular application of it, not of the tag itself. {@code null} means binary "has the tag"; present
 * means a real match-strength signal, typically supplied by a classifier.
 *
 * <p>{@link #source()} is {@code "manual"} or {@code "auto"} -- so an auto-classifier run can be diffed and
 * reapplied without disturbing manually-applied tags on the same instance; see doc/spec/tagging.md's
 * "Classification".
 */
public final class Tagging {

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_AUTO = "auto";

    private final UUID id;
    private final Tag tag;
    private final String taggableType;
    private final UUID taggableId;
    private final Double affinity;
    private final String source;
    private final Instant createdAt;

    public Tagging(UUID id, Tag tag, String taggableType, UUID taggableId, Double affinity, String source,
            Instant createdAt) {
        this.id = id;
        this.tag = tag;
        this.taggableType = taggableType;
        this.taggableId = taggableId;
        this.affinity = affinity;
        this.source = source;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public Tag tag() {
        return tag;
    }

    public String taggableType() {
        return taggableType;
    }

    public UUID taggableId() {
        return taggableId;
    }

    public Double affinity() {
        return affinity;
    }

    public String source() {
        return source;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TaggableRef taggableRef() {
        return new TaggableRef(taggableType, taggableId);
    }
}
