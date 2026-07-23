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

/** Hop two of {@link AssocChainTop}'s chain -- lazy and vectorizable on both sides. */
@Entity
@JavAIVectorizable
public class AssocChainMiddle {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    @Summary
    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private AssocLeaf leaf;

    public AssocChainMiddle() {
    }

    public AssocChainMiddle(String label, AssocLeaf leaf) {
        this.label = label;
        this.leaf = leaf;
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

    public AssocLeaf getLeaf() {
        return leaf;
    }

    public void setLeaf(AssocLeaf leaf) {
        this.leaf = leaf;
    }
}
