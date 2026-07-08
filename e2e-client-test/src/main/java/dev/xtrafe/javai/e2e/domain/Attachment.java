package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.SearchVisibility;
import dev.xtrafe.javai.annotations.Vectorize;

import static dev.xtrafe.javai.annotations.SearchVisibility.Visibility.PRIVATE;

/**
 * {@code @SearchVisibility(PRIVATE)} at the *type* level, not the field level -- the other axis from
 * {@link Article#getDraftComment()}. An {@code Attachment} is reachable through {@link Article#attachment},
 * an ordinary, non-hidden field, so {@code query()} traverses into it exactly as it would any other node;
 * it just can never itself be returned as a match for {@code query(reference, Attachment.class)}.
 * {@link #relatedComment} exists to prove that distinction for real: traversal must still reach it via
 * {@code query(reference, Comment.class)} even though it passes straight through a type-hidden node to get
 * there.
 */
@JavAIVectorizable
@SearchVisibility(PRIVATE)
public class Attachment {

    @Vectorize
    private String filename;

    private Comment relatedComment;

    public Attachment() {
    }

    public Attachment(String filename) {
        this.filename = filename;
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
