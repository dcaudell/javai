package dev.xtrafe.javai.e2e.domain.assoc;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * The control target: an ordinary JPA {@code @Entity} that is deliberately <b>not</b>
 * {@code @JavAIVectorizable}. An association to this never reproduced OMI-161, which is exactly why it is
 * worth persisting alongside the failing shapes -- it pins the fault to the vector-write walk rather than
 * to JPA association handling in general, and it proves a non-vectorizable relation still round-trips
 * without ever acquiring a vector row of its own.
 */
@Entity
public class PlainLeaf {

    @Id
    private UUID id = UUID.randomUUID();

    private String label;

    public PlainLeaf() {
    }

    public PlainLeaf(String label) {
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
