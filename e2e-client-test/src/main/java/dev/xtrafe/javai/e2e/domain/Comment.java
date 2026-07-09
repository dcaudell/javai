package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.PromptContext;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.annotations.VectorizeIgnore;
import jakarta.persistence.Entity;
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
 * {@code JavAIWeaver}'s hermetic test already proves in isolation. Deliberately not {@code @PromptContext}
 * either, for the same reason its name suggests: internal, never meant to surface -- not in an embedding,
 * not in a prompt.
 *
 * <p>{@code @Entity} + {@link #id}: a real, independently-persistable entity on both backends --
 * {@link Article#getFeaturedComment()}/{@link Article#getDraftComment()} are real Postgres
 * {@code @OneToOne}s, and {@link Article#getComments()} round-trips through
 * {@code javai-persistence}'s own collection-membership mechanism (Postgres) or a real graph relationship
 * (Neo4j) -- see {@code PersistenceE2ETest}. Comment has no derived queries of its own; see
 * {@code CommentRepository}'s javadoc for why it's still realized as a repository once, regardless.
 */
@Entity
@JavAIVectorizable
public class Comment extends Attribution {

    @Id
    private UUID id;

    @Vectorize
    @PromptContext
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
