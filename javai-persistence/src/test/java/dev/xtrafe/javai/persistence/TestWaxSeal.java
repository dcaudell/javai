package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * A <b>final</b> entity class, which is the whole point of it.
 *
 * <p>Hibernate builds a lazy proxy by subclassing the entity, so a final class cannot be proxied and every
 * lazy to-one pointing at it degrades to eager — silently, with nothing in the mapping to suggest it. This
 * fixture exists so that behaviour is pinned rather than rediscovered; {@link TestSeal} is its non-final
 * twin, and the pair together separate finality from every other reason a to-one might not be lazy.
 */
@Entity
final class TestWaxSeal {

    @Id
    private UUID id;

    private String impression;

    TestWaxSeal() {
    }

    TestWaxSeal(String impression) {
        this.impression = impression;
    }

    UUID getId() {
        return id;
    }

    String getImpression() {
        return impression;
    }
}
