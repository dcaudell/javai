package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/**
 * The FK-owning side of {@link AssocBiParent}'s bidirectional association. Its {@code parent} field is a
 * lazy {@code @ManyToOne} to a vectorizable -- OMI-161's exact failing shape, reached this time through a
 * back-reference rather than a forward one, and forming a cycle with the parent's own child list.
 */
@Entity
@JavAIVectorizable
public class AssocBiChild {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    @ManyToOne(fetch = FetchType.LAZY)
    private AssocBiParent parent;

    public AssocBiChild() {
    }

    public AssocBiChild(String label) {
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

    public AssocBiParent getParent() {
        return parent;
    }

    public void setParent(AssocBiParent parent) {
        this.parent = parent;
    }
}
