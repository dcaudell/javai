package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Id;

import org.hibernate.annotations.Any;
import org.hibernate.annotations.AnyDiscriminator;
import org.hibernate.annotations.AnyDiscriminatorValue;
import org.hibernate.annotations.AnyKeyJavaClass;
import org.hibernate.annotations.Cascade;

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

    /**
     * A polymorphic to-one <em>on a leaf</em>, whose only target is {@link AssocNestedAny} (OMI-212).
     *
     * <p>Position matters here, not configuration. Every other {@code @Any} in this matrix hangs off
     * {@link AssocHub}, one hop from the repository a caller actually asks for. This one is two: discovery
     * has to follow an ordinary association to reach this class at all, and only then read a discriminator
     * on the far side. A fix that registered discriminator targets on the root entity but did not recurse
     * would pass every other test in this file and fail this one.
     */
    @Cascade({org.hibernate.annotations.CascadeType.PERSIST, org.hibernate.annotations.CascadeType.MERGE})
    // Eager on purpose: what this field proves is that discovery RECURSES to reach a discriminator two
    // hops out. Fetch mode is covered on the hub's own @Any fields, and making this lazy would only mean
    // the assertion had to be about LazyInitializationException instead of about registration.
    @Any(fetch = FetchType.EAGER)
    @AnyDiscriminator(DiscriminatorType.STRING)
    @AnyDiscriminatorValue(discriminator = "nested", entity = AssocNestedAny.class)
    @AnyKeyJavaClass(UUID.class)
    @Column(name = "nested_any_type")
    @JoinColumn(name = "nested_any_id")
    private AssocAnyTarget nestedAny;

    public AssocAnyTarget getNestedAny() {
        return nestedAny;
    }

    public void setNestedAny(AssocAnyTarget value) {
        this.nestedAny = value;
    }

}
