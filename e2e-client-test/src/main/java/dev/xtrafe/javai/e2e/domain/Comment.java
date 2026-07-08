package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.annotations.VectorizeIgnore;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * Client code: an ordinary annotated class, no {@code implements JavAIVectorizable}, no hand-written
 * {@code vector()}. The {@code javai-agent} weaver synthesizes all of that at class-load time. Mirrors
 * the whitepaper's own Article/Comment worked example (doc/spec/end-to-end-example.md).
 *
 * <p>{@code author} lives on {@link Attribution}, a plain (non-{@code @JavAIVectorizable}) superclass --
 * see that class's javadoc for what this proves about the weaver.
 *
 * <p>{@code internalModerationNote} carries both {@code @Vectorize} and {@code @VectorizeIgnore} on the
 * same field -- the explicit exclude signal must win against real weaving and real embeddings, exactly as
 * {@code JavAIWeaver}'s hermetic test already proves in isolation.
 *
 * <p>{@link #id} exists purely for {@code javai-persistence}: a comment becomes a related Neo4j node when
 * reached through {@link Article#getFeaturedComment()}/{@link Article#getComments()} (both
 * {@code @Summary} fields), which requires its own {@code @Id}. Comment has no repository of its own --
 * see {@code PersistenceE2ETest}.
 */
@JavAIVectorizable
public class Comment extends Attribution {

    @Id
    private UUID id;

    @Vectorize
    private String text;

    @Vectorize
    @VectorizeIgnore
    private String internalModerationNote;

    public Comment() {
    }

    public Comment(String author, String text) {
        setAuthor(author);
        this.text = text;
    }

    public UUID getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getInternalModerationNote() {
        return internalModerationNote;
    }

    public void setInternalModerationNote(String internalModerationNote) {
        this.internalModerationNote = internalModerationNote;
    }
}
