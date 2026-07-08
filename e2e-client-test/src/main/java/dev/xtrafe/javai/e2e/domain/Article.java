package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.runtime.JavAIArrayList;

/**
 * Client code exercising both containment shapes doc/spec/vector-core.md's {@code summaryVector()}
 * formula covers: a single {@code @Summary} reference ({@link #featuredComment}) and a
 * {@code @Summary} collection ({@link #comments}, initialized inline and never reassigned -- elements
 * are added through the collection itself, exercising {@code javai-agent}'s constructor-exit wiring for
 * that case).
 */
@JavAIVectorizable
public class Article {

    @Vectorize
    private String title;

    @Vectorize
    private String body;

    @Summary
    private Comment featuredComment;

    @Summary
    private final JavAIArrayList<Comment> comments = new JavAIArrayList<>();

    public Article() {
    }

    public Article(String title, String body) {
        this.title = title;
        this.body = body;
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
}
