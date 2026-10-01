package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** The target of {@link TestBadgeHolder}'s {@code optional = false} {@code @OneToOne}. */
// ⚠️ NOT final, deliberately: Hibernate builds a lazy proxy by subclassing the entity, so a
// final class cannot be proxied and every lazy to-one pointing at it degrades to eager. This
// fixture is the target of a non-optional @OneToOne, and it must be proxyable for the
// test to be measuring what it claims to measure rather than finality (OMI-279).
@Entity
class TestSeal {

    @Id
    private UUID id;

    private String stamp;

    TestSeal() {
    }

    TestSeal(String stamp) {
        this.stamp = stamp;
    }

    UUID getId() {
        return id;
    }

    String getStamp() {
        return stamp;
    }
}
