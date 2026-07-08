package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;

/**
 * Client code: an ordinary annotated class, no {@code implements JavAIVectorizable}, no hand-written
 * {@code vector()}. The {@code javai-agent} weaver synthesizes all of that at class-load time. Mirrors
 * the whitepaper's own Article/Comment worked example (doc/spec/end-to-end-example.md).
 */
@JavAIVectorizable
public class Comment {

    @Vectorize
    private String author;

    @Vectorize
    private String text;

    public Comment() {
    }

    public Comment(String author, String text) {
        this.author = author;
        this.text = text;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
