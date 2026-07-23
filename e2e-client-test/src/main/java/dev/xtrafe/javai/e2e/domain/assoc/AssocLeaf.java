package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * The shared <em>target</em> of every association shape in this package, and itself
 * {@code @JavAIVectorizable} -- which is the entire point of OMI-161. A vectorizable owner pointing at a
 * <em>vectorizable</em> target is the shape that failed; pointing at {@link PlainLeaf} (same package, not
 * vectorizable) never did, and is kept alongside as the control.
 *
 * <p>Note what is <b>not</b> written here: no {@code implements JavAIVectorizable}, and no {@code vector()}
 * method. Both are added by the load-time weaver -- see this package's {@link AssocHub} javadoc for why
 * load-time weaving specifically matters to this regression.
 */
@Entity
@JavAIVectorizable
public class AssocLeaf {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    public AssocLeaf() {
    }

    public AssocLeaf(String label) {
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
