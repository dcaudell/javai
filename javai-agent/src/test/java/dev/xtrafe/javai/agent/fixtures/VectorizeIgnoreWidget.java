package dev.xtrafe.javai.agent.fixtures;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.annotations.VectorizeIgnore;

/** {@code excluded} carries both annotations -- proves {@code @VectorizeIgnore} wins over {@code @Vectorize}. */
@JavAIVectorizable
public class VectorizeIgnoreWidget {

    @Vectorize
    private String included;

    @Vectorize
    @VectorizeIgnore
    private String excluded;

    public void setIncluded(String included) {
        this.included = included;
    }

    public void setExcluded(String excluded) {
        this.excluded = excluded;
    }
}
