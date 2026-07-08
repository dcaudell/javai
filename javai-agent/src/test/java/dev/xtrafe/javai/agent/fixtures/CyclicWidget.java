package dev.xtrafe.javai.agent.fixtures;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;

/** Minimal self-referential fixture proving woven classes hold up under a reference cycle. */
@JavAIVectorizable
public class CyclicWidget {

    @Vectorize
    private String label;

    @Summary
    private CyclicWidget next;

    public CyclicWidget() {
    }

    public CyclicWidget(String label) {
        this.label = label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setNext(CyclicWidget next) {
        this.next = next;
    }
}
