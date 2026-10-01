package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import java.util.UUID;

/**
 * A Taggregate container of {@link Article}s and {@link Comment}s -- the bottom tier of this project's
 * three-level nesting fixture ({@link Library} of {@link Shelf} of {@code Anthology}), and the class that
 * exercises every {@code @Taggregate} placement at once: two collection fields of <em>different</em> member
 * types (heterogeneous aggregation across one container's fields), a singular reference field
 * ({@link #featureArticle}), and the type-level {@code concatenate = true} tag-text opt-in.
 *
 * <p>Deliberately <b>not</b> {@code @JavAIVectorizable} -- the lineage rule's own pin, from real client
 * code: a container needs only the {@code Taggable} marker interface and an {@code @Id UUID}, while its
 * members ({@code Article}, woven; {@code Comment}, woven) bring whatever lineage they like. {@link Shelf},
 * one tier up, is the opposite mix: a woven container of these plain ones.
 *
 * <p>The member fields are ordinary OMI-142-shaped associations (JavAI interface type, non-final, plain JPA
 * annotation), so a persisted anthology's members load lazily and the Taggregate machinery's reflective
 * walk must resolve real Hibernate state -- exactly what an adopter's {@code Album} will look like.
 * The article collection is {@code @ManyToMany} so one article can belong to several anthologies, which is
 * what lets a test pin an update fanning out across a diamond -- containment reads the join table, so every
 * container holding a tagged member is found, not merely the first.
 */
@Entity
@dev.xtrafe.javai.annotations.Taggable
@Taggregate(concatenate = true)
public class Anthology implements dev.xtrafe.javai.tagging.Taggable {

    @Id
    private UUID id;

    private String name;

    @OneToOne(cascade = CascadeType.ALL)
    @Taggregate
    private Article featureArticle;

    // @ManyToMany, so one article can sit in several anthologies -- the diamond an aggregate has to fan
    // an update out across, and impossible to express with a single-parent @OneToMany.
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(name = "anthology_articles")
    @Taggregate
    private JavAIList<Article> articles = new JavAIArrayList<>();

    @OneToMany(cascade = CascadeType.ALL)
    @Taggregate
    private JavAIList<Comment> comments = new JavAIArrayList<>();

    public Anthology() {
    }

    public Anthology(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Article getFeatureArticle() {
        return featureArticle;
    }

    public void setFeatureArticle(Article featureArticle) {
        this.featureArticle = featureArticle;
    }

    public JavAIList<Article> getArticles() {
        return articles;
    }

    public JavAIList<Comment> getComments() {
        return comments;
    }
}
