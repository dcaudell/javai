package dev.xtrafe.javai.agent.fixtures;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;

/** A leaf node in the weaving test's object graph -- see {@link GraphContainer}. */
@JavAIVectorizable
public class GraphChild {

    @Vectorize
    private String text;

    public GraphChild() {
    }

    public GraphChild(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
