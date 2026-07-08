package dev.xtrafe.javai.agent.fixtures;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.runtime.JavAIArrayList;

/**
 * Proves both containment shapes doc/spec/vector-core.md's summaryVector() formula covers: a single
 * {@code @Summary} reference ({@link #featured}) and a {@code @Summary} collection ({@link #items},
 * initialized inline and never reassigned -- exercising {@link dev.xtrafe.javai.agent.ConstructorExitAdvice},
 * not just setter-based edges).
 */
@JavAIVectorizable
public class GraphContainer {

    @Vectorize
    private String label;

    @Summary
    private GraphChild featured;

    @Summary
    private final JavAIArrayList<GraphChild> items = new JavAIArrayList<>();

    public GraphContainer() {
    }

    public GraphContainer(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public GraphChild getFeatured() {
        return featured;
    }

    public void setFeatured(GraphChild featured) {
        this.featured = featured;
    }

    public JavAIArrayList<GraphChild> getItems() {
        return items;
    }
}
