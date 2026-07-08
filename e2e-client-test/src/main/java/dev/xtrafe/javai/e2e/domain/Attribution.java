package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.Vectorize;

/**
 * Deliberately plain -- no {@code @JavAIVectorizable} of its own. {@link Comment} is the concrete,
 * annotated leaf that extends it without redeclaring {@link #setAuthor}. Exercises the weaver's
 * inherited-setter-override synthesis (see {@code JavAIWeaver}'s javadoc) against real embeddings, not
 * just the hermetic fixture in javai-agent's own test suite: the weaver synthesizes
 * {@code public void setAuthor(String value) { super.setAuthor(value); } } on {@code Comment} at
 * class-load time so mutating an inherited field still marks the leaf object dirty.
 */
public class Attribution {

    @Vectorize
    private String author;

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }
}
