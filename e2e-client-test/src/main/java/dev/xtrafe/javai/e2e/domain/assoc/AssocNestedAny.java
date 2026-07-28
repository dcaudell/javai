package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * An {@code @Any} target reachable <b>only</b> through {@link AssocLeaf}'s own polymorphic field -- which is
 * itself reachable only from {@link AssocHub}. Registering it therefore requires discovery to follow an
 * ordinary association <em>and then</em> read a discriminator on the far side, two hops from the repository
 * anyone actually asks for.
 *
 * <p>Nothing else in this package may reference this type. The moment something does, it becomes reachable
 * by the ordinary field walk and the nested-position test starts passing for the wrong reason.
 */
@Entity
@JavAIVectorizable
public class AssocNestedAny implements AssocAnyTarget {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    protected AssocNestedAny() {
    }

    public AssocNestedAny(String label) {
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
