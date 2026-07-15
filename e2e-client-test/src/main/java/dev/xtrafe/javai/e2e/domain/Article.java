package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.PromptContext;
import dev.xtrafe.javai.annotations.SearchVisibility;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Taggable;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.collections.JavAIGraphNode;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

import java.util.UUID;

import static dev.xtrafe.javai.annotations.SearchVisibility.Visibility.PRIVATE;

/**
 * Client code exercising both containment shapes doc/spec/vector-core.md's {@code summaryVector()}
 * formula covers: a single {@code @Summary} reference ({@link #featuredComment}) and a
 * {@code @Summary} collection ({@link #comments}, initialized inline and never reassigned -- elements
 * are added through the collection itself, exercising {@code javai-substrate}'s constructor-exit wiring for
 * that case).
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
 * collection-proxy substitution. {@code comments}/{@code relatedComments} are the fields that would: a
 * concrete JavAI collection class ({@code JavAIArrayList}/{@code JavAILinkedHashMap}) can never be a native
 * Hibernate-mapped collection field (confirmed empirically -- a {@code ClassCastException} the moment
 * Hibernate tries to substitute its own {@code PersistentBag}/{@code PersistentMap} into a field statically
 * typed as the concrete JavAI class). Note there's no {@code @Transient} here, though, and no manual
 * repository pre-registration for {@code Comment} either: {@code RepositoryBackendHibernatePostgres}
 * auto-detects both of these fields reflectively and excludes them from Hibernate's own mapping itself, and
 * auto-registers {@code Comment} as reachable through them -- see that class's javadoc ("No manual
 * {@code @Transient} required" / "Related entity types are auto-registered too"). Both fields instead
 * round-trip through {@code javai-persistence}'s own {@code javai_collection_members} side table, which,
 * being reflective rather than proxy-based, hydrates a real {@code JavAIArrayList}/{@code JavAILinkedHashMap}
 * back (full dirty-tracking intact) exactly like Neo4j's own reflective relationship mapping already does.
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

    @Summary
    private final JavAIArrayList<Comment> comments = new JavAIArrayList<>();

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

    public JavAIArrayList<Comment> getComments() {
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
