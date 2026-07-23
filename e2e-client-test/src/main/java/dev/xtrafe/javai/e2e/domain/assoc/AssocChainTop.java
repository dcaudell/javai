package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/**
 * Depth. {@code AssocChainTop -> AssocChainMiddle -> AssocLeaf}, every hop lazy and every hop
 * vectorizable, so the graph is deeper than the single hop {@link AssocHub} covers.
 *
 * <p>Worth its own shape because the two vector-write walks reach different distances: the explicit
 * related-entity walk only looks one hop out from the entity being saved, while anything further is picked
 * up by the post-flush sweep over what Hibernate cascaded. A one-hop-only regression test would pass while
 * hop two silently lost its vectors, so this asserts all three levels land rows.
 */
@Entity
@JavAIVectorizable
public class AssocChainTop {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    @Summary
    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private AssocChainMiddle middle;

    public AssocChainTop() {
    }

    public AssocChainTop(String label, AssocChainMiddle middle) {
        this.label = label;
        this.middle = middle;
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

    public AssocChainMiddle getMiddle() {
        return middle;
    }

    public void setMiddle(AssocChainMiddle middle) {
        this.middle = middle;
    }
}
