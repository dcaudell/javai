package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/**
 * Self-reference: a lazy {@code @ManyToOne} to its own type. The degenerate case of "a vectorizable
 * pointing at a vectorizable", and the one where a naive proxy-resolving fix could recurse forever if it
 * chose to initialize proxies instead of skipping untouched ones.
 *
 * <p>Also the cycle-safety shape at the persistence layer, mirroring what {@code doc/spec/vector-core.md}
 * already guarantees in memory for {@code summaryVector()}'s back-edge walk.
 */
@Entity
@JavAIVectorizable
public class AssocSelfNode {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    @ManyToOne(fetch = FetchType.LAZY)
    private AssocSelfNode parent;

    public AssocSelfNode() {
    }

    public AssocSelfNode(String label) {
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

    public AssocSelfNode getParent() {
        return parent;
    }

    public void setParent(AssocSelfNode parent) {
        this.parent = parent;
    }
}
