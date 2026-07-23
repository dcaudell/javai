package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The owning-read side of a bidirectional association: {@code @OneToMany(mappedBy)} here, the lazy
 * {@code @ManyToOne} that actually owns the foreign key on {@link AssocBiChild}.
 *
 * <p>Bidirectionality matters to this regression for a specific reason: the child's back-reference to its
 * parent is a lazy singular association to a vectorizable, so saving a child walks straight into the OMI-161
 * shape -- and the resulting object graph contains a cycle (parent -> children -> parent), which the vector
 * walk has to traverse without either recursing forever or writing the parent's row twice.
 *
 * <p>The original ticket also raised, and then ruled out, cascade as the cause. It is exercised rather than
 * argued about: {@code cascade = ALL} here, none on the child's own back-reference.
 */
@Entity
@JavAIVectorizable
public class AssocBiParent {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    @Summary
    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<AssocBiChild> children = new ArrayList<>();

    public AssocBiParent() {
    }

    public AssocBiParent(String label) {
        this.label = label;
    }

    /** Keeps both sides consistent, the ordinary JPA way -- the child owns the FK. */
    public void addChild(AssocBiChild child) {
        children.add(child);
        child.setParent(this);
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

    public List<AssocBiChild> getChildren() {
        return children;
    }
}
