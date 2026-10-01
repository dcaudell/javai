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
import dev.xtrafe.javai.model.JavAIMap;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.MapKeyColumn;
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
 * collection-proxy substitution.
 *
 * <p>Both collection fields are declared by a JavAI <em>interface</em> ({@code JavAIList}/{@code JavAIMap}),
 * non-final, with an ordinary JPA annotation -- genuine, natively Hibernate-managed associations (their own
 * join tables, cascade, lazy loading), with a {@code PersistentJavAIList}/{@code PersistentJavAIMap}
 * substituted into the field so vectors and dirty-tracking survive. This class used to carry <em>one shape
 * each</em>, {@code relatedComments} being concrete-typed and unannotated so it exercised the membership
 * table alongside the native mapping; OMI-277 withdrew that shape and deleted the table, and this fixture
 * migrated with it. The two fields now differ in cardinality ({@code List} vs. {@code Map}) and cascade
 * rather than in storage.
 *
 * <p>Note there is no {@code @Transient} anywhere here, and no manual repository pre-registration for
 * {@code Comment} either: {@code RepositoryBackendHibernatePostgres} auto-registers {@code Comment} as
 * reachable through either field -- see that class's javadoc ("Related entity types are auto-registered
 * too").
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
     * {@code RepositoryBackendHibernatePostgres} at mapping time.
     */
    @OneToMany(cascade = CascadeType.ALL)
    @Summary
    private JavAIList<Comment> comments = new JavAIArrayList<>();

    // Not @Summary -- purely exercises JavAIMap persistence alongside comments' JavAIList, without changing
    // what already-passing tests assert about summaryVector().
    // Its own join table, explicitly: `comments` above is also a to-many of Comment, and Hibernate derives
    // the default join-table name from owner + element type, so both would claim `article_comment`.
    @OneToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(name = "article_related_comment")
    @MapKeyColumn(name = "related_key")
    private JavAIMap<String, Comment> relatedComments = new JavAILinkedHashMap<>();

    @OneToOne(cascade = CascadeType.ALL)
    @SearchVisibility(PRIVATE)
    private Comment draftComment;

    @OneToOne(cascade = CascadeType.ALL)
    private Attachment attachment;

    // ---- counters maintained outside this entity's own editing path (OMI-398) --------------------
    //
    // Neither is @Vectorize, and that is the point: they sit on a genuinely woven @JavAIVectorizable class,
    // so a targeted write to one has to be allowed while a write to title/body is refused. The two differ in
    // the one dimension the ticket cared about.

    /** Ordinary: writable by save() and by a targeted @Modifying query alike. */
    private long viewCount;

    /**
     * Read-only to save(), writable only through a targeted @Modifying query -- the ticket's motivating
     * shape. A count moved by its own path (a like, a settled charge) is otherwise clobberable by any
     * unrelated edit: load the article before the count moved, fix a typo in the title, save, and the stale
     * count goes back with it. This repository hands out detached entities, which is exactly the shape that
     * goes stale. JPA's own flag is the whole mechanism; JavAI adds no annotation for it.
     */
    @Column(updatable = false)
    private long likeCount;

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

    public long getViewCount() {
        return viewCount;
    }

    public void setViewCount(long viewCount) {
        this.viewCount = viewCount;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(long likeCount) {
        this.likeCount = likeCount;
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

    public JavAIMap<String, Comment> getRelatedComments() {
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
