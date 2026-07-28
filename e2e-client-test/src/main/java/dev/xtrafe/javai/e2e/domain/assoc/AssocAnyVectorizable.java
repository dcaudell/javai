package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * An {@code @Any} target that <em>is</em> vectorizable -- so a polymorphic reference resolving to this must
 * also get its vectors written, exactly as a fixed-type association to {@link AssocLeaf} does.
 *
 * <p>Load-time woven like everything else in this package; no {@code implements JavAIVectorizable} and no
 * {@code vector()} here for that reason. See {@link AssocHub}'s javadoc.
 */
@Entity
@JavAIVectorizable
public class AssocAnyVectorizable implements AssocAnyTarget {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    protected AssocAnyVectorizable() {
    }

    public AssocAnyVectorizable(String label) {
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }
}
