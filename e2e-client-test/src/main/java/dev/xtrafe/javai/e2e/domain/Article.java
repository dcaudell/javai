package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.SearchVisibility;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.collections.JavAIGraphNode;
import dev.xtrafe.javai.runtime.JavAIArrayList;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

import java.util.UUID;

import static dev.xtrafe.javai.annotations.SearchVisibility.Visibility.PRIVATE;

/**
 * Client code exercising both containment shapes doc/spec/vector-core.md's {@code summaryVector()}
 * formula covers: a single {@code @Summary} reference ({@link #featuredComment}) and a
 * {@code @Summary} collection ({@link #comments}, initialized inline and never reassigned -- elements
 * are added through the collection itself, exercising {@code javai-agent}'s constructor-exit wiring for
 * that case).
 *
 * <p>{@link #draftComment} is field-level {@code @SearchVisibility(PRIVATE)}: reachable in the object
 * graph exactly like {@link #featuredComment}, but {@code query()} must never traverse through it, so
 * nothing reachable only via this field -- including the draft comment itself -- can ever surface as a
 * hit. {@link #attachment} is the other axis: an ordinary, non-hidden field pointing at a class that's
 * {@code @SearchVisibility(PRIVATE)} at the *type* level instead, so traversal passes through it freely,
 * it just can't itself be returned as a match; see {@link Attachment}.
 *
 * <p>{@code implements JavAIGraphNode} lets {@code Article} instances participate directly in a
 * {@code javai-collections} {@code KnowledgeGraph} -- a hand-declared, unwoven marker interface, per that
 * module's own README.
 *
 * <p>{@code @Entity} + {@link #id}, for {@code javai-persistence}: {@link #getId()} is what
 * {@code ArticleRepository} (see {@code PersistenceE2ETest}) uses as the JavAI-fixed {@code UUID} identity
 * across both the Postgres and Neo4j backends. {@code featuredComment}/{@code comments}/
 * {@code draftComment}/{@code attachment} are all {@code @Transient}: Hibernate has no JPA association
 * annotation to map them by (that's real, separate scope -- teaching Hibernate to persist a
 * {@code JavAIArrayList} as a relational collection -- not needed to prove the vector storage/query
 * contract this test targets), so Hibernate simply ignores them; Neo4j's backend, by contrast, doesn't
 * look at {@code @Transient} at all, so {@code featuredComment}/{@code comments} (both {@code @Summary})
 * still become real relationships there, same as in-memory.
 */
@Entity
@JavAIVectorizable
public class Article implements JavAIGraphNode {

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @Vectorize
    private String body;

    @Transient
    @Summary
    private Comment featuredComment;

    @Transient
    @Summary
    private final JavAIArrayList<Comment> comments = new JavAIArrayList<>();

    @Transient
    @SearchVisibility(PRIVATE)
    private Comment draftComment;

    @Transient
    private Attachment attachment;

    public Article() {
    }

    public Article(String title, String body) {
        this.title = title;
        this.body = body;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Comment getFeaturedComment() {
        return featuredComment;
    }

    public void setFeaturedComment(Comment featuredComment) {
        this.featuredComment = featuredComment;
    }

    public JavAIArrayList<Comment> getComments() {
        return comments;
    }

    public Comment getDraftComment() {
        return draftComment;
    }

    public void setDraftComment(Comment draftComment) {
        this.draftComment = draftComment;
    }

    public Attachment getAttachment() {
        return attachment;
    }

    public void setAttachment(Attachment attachment) {
        this.attachment = attachment;
    }
}
