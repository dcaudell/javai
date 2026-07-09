package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.SearchVisibility;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

import java.util.UUID;

import static dev.xtrafe.javai.annotations.SearchVisibility.Visibility.PRIVATE;

/**
 * {@code @SearchVisibility(PRIVATE)} at the *type* level, not the field level -- the other axis from
 * {@link Article#getDraftComment()}. An {@code Attachment} is reachable through {@link Article#attachment},
 * an ordinary, non-hidden field, so {@code query()} traverses into it exactly as it would any other node;
 * it just can never itself be returned as a match for {@code query(reference, Attachment.class)}.
 * {@link #relatedComment} exists to prove that distinction for real: traversal must still reach it via
 * {@code query(reference, Comment.class)} even though it passes straight through a type-hidden node to get
 * there.
 *
 * <p>{@code @Entity} + {@link #id}: {@link Article#getAttachment()} is a real {@code @OneToOne} on
 * Postgres (see {@code PersistenceE2ETest}). {@link #relatedComment} stays {@code @Transient} for
 * Hibernate specifically -- it exists purely to test in-memory {@code query()} traversal depth, one level
 * further than this project's persistence fidelity needs to reach for Postgres. Neo4j's reflective
 * mapper has no equivalent "must annotate every hop" constraint, so it picks this field up as a real
 * relationship anyway (see {@code Neo4jRepositoryBackend}'s javadoc) -- an asymmetry that's inherent to
 * the two technologies, not a gap.
 */
@Entity
@JavAIVectorizable
@SearchVisibility(PRIVATE)
public class Attachment {

    @Id
    private UUID id;

    @Vectorize
    private String filename;

    @Transient
    private Comment relatedComment;

    public Attachment() {
    }

    public Attachment(String filename) {
        this.filename = filename;
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Comment getRelatedComment() {
        return relatedComment;
    }

    public void setRelatedComment(Comment relatedComment) {
        this.relatedComment = relatedComment;
    }
}
