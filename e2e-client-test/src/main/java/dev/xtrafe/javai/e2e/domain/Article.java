package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.PromptContext;
import dev.xtrafe.javai.annotations.SearchVisibility;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Taggable;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.collections.JavAIGraphNode;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import java.util.UUID;

import static dev.xtrafe.javai.annotations.SearchVisibility.Visibility.PRIVATE;

/**
 * Client code exercising both containment shapes doc/spec/vector-core.md's {@code summaryVector()}
 * formula covers: a single {@code @Summary} reference ({@link #featuredComment}) and a
 * {@code @Summary} collection ({@link #comments}, initialized inline and never reassigned by application
 * code -- elements are added through the collection itself, exercising {@code javai-substrate}'s
 * constructor-exit wiring for that case; Hibernate does substitute its own {@code PersistentJavAIList} into
 * the field when loading a persisted instance, which is the point of the OMI-142 shape described below).
 *
 * <p>{@link #title}/{@link #body} also carry {@code @PromptContext} -- Completion Fabric's field-level
 * allowlist for {@code PromptContext.defaultMarshall(Object)} -- so an {@code Article} wrapped in
 * {@code ContextableObject} renders as just those two fields, never {@link #id} or any woven internal
 * state; see {@code CompletionE2ETest}.
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
 * module's own README. {@code implements dev.xtrafe.javai.tagging.Taggable} (referenced by fully-qualified
 * name in the {@code implements} clause below, since its simple name {@code Taggable} collides with the
 * {@code @Taggable} annotation imported above) is the analogous unwoven marker for {@code javai-tagging} --
 * both interfaces are hand-declared, never woven, and freely composable per each module's own orthogonality
 * principle; see {@code TaggingE2ETest}.
 *
 * <p>{@code @Entity} + {@link #id}, for {@code javai-persistence}: {@link #getId()} is what
 * {@code ArticleRepository} (see {@code PersistenceE2ETest}) uses as the JavAI-fixed {@code UUID} identity
 * across both the Postgres and Neo4j backends. {@code featuredComment}/{@code draftComment}/
 * {@code attachment} are real {@code @OneToOne(cascade = CascadeType.ALL)} associations -- ordinary
 * Hibernate relational mapping, since a *singular* reference field never collides with Hibernate's own
 * collection-proxy substitution. The two collection fields deliberately carry <em>one shape each</em>, so
 * this one class exercises both halves of OMI-142 side by side:
 *
 * <p>{@code comments} is declared by the JavAI <em>interface</em> ({@code JavAIList}) and non-final, with an
 * ordinary {@code @OneToMany} -- a genuine, natively Hibernate-managed association (its own join table,
 * cascade, lazy loading), with a {@code PersistentJavAIList} substituted into the field so vectors and
 * dirty-tracking survive. {@code relatedComments} is declared by the <em>concrete</em> class
 * ({@code JavAILinkedHashMap}) and unannotated, which can never be a natively mapped collection field
 * (confirmed empirically -- a {@code ClassCastException} the moment Hibernate tries to substitute its own
 * {@code PersistentMap} into a field statically typed as the concrete JavAI class), so it keeps JavAI's own
 * {@code javai_collection_members} side-table storage, hydrated reflectively rather than by proxy, exactly
 * like Neo4j's own relationship mapping already does. Note there's no {@code @Transient} on it, though, and
 * no manual repository pre-registration for {@code Comment} either: {@code RepositoryBackendHibernatePostgres}
 * auto-detects the side-table field reflectively and excludes it from Hibernate's own mapping itself, and
 * auto-registers {@code Comment} as reachable through either field -- see that class's javadoc ("No manual
 * {@code @Transient} required" / "Related entity types are auto-registered too").
 */
@Entity
@JavAIVectorizable
@Taggable
public class Article implements JavAIGraphNode, dev.xtrafe.javai.tagging.Taggable {

    @Id
    private UUID id;

    @Vectorize
    @PromptContext
    private String title;

    @Vectorize
    @PromptContext
    private String body;

    @OneToOne(cascade = CascadeType.ALL)
    @Summary
    private Comment featuredComment;

    /**
     * OMI-142: declared by the JavAI <em>interface</em> and non-final, with an ordinary JPA
     * {@code @OneToMany} -- so Hibernate owns this association natively (its own join table, cascade, lazy
     * loading) while the instance it substitutes into the field is still a real JavAI collection with
     * vectors and dirty-tracking. Nothing JavAI-specific is written here: the collection type is attached by
     * {@code RepositoryBackendHibernatePostgres} at mapping time. Contrast {@link #relatedComments} below,
     * which stays on JavAI's own side-table storage -- the two shapes coexist deliberately.
     */
    @OneToMany(cascade = CascadeType.ALL)
    @Summary
    private JavAIList<Comment> comments = new JavAIArrayList<>();

    // Not @Summary -- purely exercises JavAILinkedHashMap persistence (String-keyed, per
    // RepositoryBackendHibernatePostgres's own documented Phase 0 limitation) alongside comments'
    // JavAIArrayList, without changing what already-passing tests assert about summaryVector().
    private final JavAILinkedHashMap<String, Comment> relatedComments = new JavAILinkedHashMap<>();

    @OneToOne(cascade = CascadeType.ALL)
    @SearchVisibility(PRIVATE)
    private Comment draftComment;

    @OneToOne(cascade = CascadeType.ALL)
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

    public JavAIList<Comment> getComments() {
        return comments;
    }

    public JavAILinkedHashMap<String, Comment> getRelatedComments() {
        return relatedComments;
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
