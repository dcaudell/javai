package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.PromptContext;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.MappedSuperclass;

/**
 * Deliberately plain -- no {@code @JavAIVectorizable} of its own. {@link Comment} is the concrete,
 * annotated leaf that extends it without redeclaring {@link #setAuthor}. Exercises the weaver's
 * inherited-setter-override synthesis (see {@code JavAIWeaver}'s javadoc) against real embeddings, not
 * just the hermetic fixture in javai-agent's own test suite: the weaver synthesizes
 * {@code public void setAuthor(String value) { super.setAuthor(value); } } on {@code Comment} at
 * class-load time so mutating an inherited field still marks the leaf object dirty.
 *
 * <p>{@code @MappedSuperclass}: without it, Hibernate would silently ignore {@link #author} on any
 * {@code @Entity} subclass (a plain, un-annotated superclass's fields aren't mapped by default) --
 * needed so {@code Comment.getAuthor()} actually round-trips through Postgres, not just Neo4j.
 *
 * <p>{@link #author} also carries {@code @PromptContext} -- GSON's reflection walks superclass fields
 * too, so this is what makes it show up when a {@code Comment} is marshalled, exactly like a directly
 * declared field on the leaf class would.
 */
@MappedSuperclass
public class Attribution {

    @Vectorize
    @PromptContext
    private String author;

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }
}
