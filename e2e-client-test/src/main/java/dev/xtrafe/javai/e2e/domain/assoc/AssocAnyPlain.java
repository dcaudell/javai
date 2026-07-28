package dev.xtrafe.javai.e2e.domain.assoc;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * The control {@code @Any} target: an ordinary {@code @Entity} that is deliberately <b>not</b>
 * {@code @JavAIVectorizable}, paired with {@link AssocAnyVectorizable} the same way {@link PlainLeaf} is
 * paired with {@link AssocLeaf} elsewhere in this matrix.
 *
 * <p>Both are reachable through the same discriminator, so a single {@code @Any} field can resolve to a
 * vectorizable target on one row and a non-vectorizable one on the next -- and the vector-write walk has to
 * cope with both without being told which to expect.
 */
@Entity
public class AssocAnyPlain implements AssocAnyTarget {

    @Id
    private UUID id = UUID.randomUUID();

    private String label;

    protected AssocAnyPlain() {
    }

    public AssocAnyPlain(String label) {
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
