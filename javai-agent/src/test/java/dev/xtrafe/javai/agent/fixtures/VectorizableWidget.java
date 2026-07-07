package dev.xtrafe.javai.agent.fixtures;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;

/**
 * Toy target for the weaving spike in {@link dev.xtrafe.javai.agent.JavAIWeaver}. As written here it
 * is a plain class -- no {@code markDirty()}, no {@code vector()}, no dirty-tracking fields. Those are
 * synthesized entirely by the weaver at class-load time; this source file is never modified to add them,
 * matching the "developer never writes the woven code" contract in doc/spec/vector-core.md.
 */
@JavAIVectorizable
public class VectorizableWidget {

    @Vectorize
    private String description;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
