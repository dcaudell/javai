package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OMI-271's control graph: a plain {@code @Entity} holding a lazy {@code @OneToMany} of {@link
 * TestPlainChild}, where <b>no type on either side is vectorized in any way</b> -- no
 * {@code JavAIVectorizable}, no {@code @Vectorize}, no {@code @Summary}, no JavAI collection, no
 * {@code Point} field.
 *
 * <p>{@link TestTeam} nearly serves this purpose but not quite: its members are {@code JavAIVectorizable},
 * which leaves room to argue that vector hydration is what pulled them in. Here there is no such room. If
 * the children load anyway, the cause is the load path's graph <em>walk</em>, independent of vectors.
 */
@Entity
final class TestPlainOwner {

    @Id
    private UUID id;

    private String label;

    @OneToMany(cascade = CascadeType.ALL)
    private List<TestPlainChild> children = new ArrayList<>();

    TestPlainOwner() {
    }

    TestPlainOwner(String label) {
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    List<TestPlainChild> getChildren() {
        return children;
    }
}
