package dev.xtrafe.javai.substrate.fixtures;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;

/**
 * Toy target for {@link dev.xtrafe.javai.substrate.JavAIWeaver}. As written here it is a plain class -- no
 * {@code markFieldDirty()}, no {@code vector()}/{@code titleVector()}/{@code bodyVector()}, no
 * dirty-tracking fields or interfaces. Those are synthesized entirely by the weaver at class-load time;
 * this source file is never modified to add them, matching the "developer never writes the woven code"
 * contract in doc/spec/vector-core.md. Two {@code @Vectorize} fields (not the single-field spike's one)
 * so the weaving test can prove per-field accessors are actually distinct, not just present.
 */
@JavAIVectorizable
public class VectorizableWidget {

    @Vectorize
    private String title;

    @Vectorize
    private String description;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
