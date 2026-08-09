package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

import java.util.UUID;

/**
 * The <b>inverse</b> side of a bidirectional {@code @OneToOne} -- the half OMI-275 never measured.
 *
 * <p>It is a genuinely different mapping from the owning side, not a mirror of it: the foreign key lives on
 * {@link TestBadgeHolder}'s table, so this side has no column to proxy from. Hibernate cannot know whether
 * the row on the other end exists without looking, which is why an inverse {@code @OneToOne} classically
 * ignores {@code LAZY} and fetches anyway. Declared {@code LAZY} here precisely so the test can say which
 * it actually does rather than which it was asked for.
 */
// ⚠️ NOT final, deliberately: Hibernate builds a lazy proxy by subclassing the entity, so a
// final class cannot be proxied and every lazy to-one pointing at it degrades to eager. This
// fixture is the inverse side of a bidirectional @OneToOne, and it must be proxyable for the
// test to be measuring what it claims to measure rather than finality (OMI-279).
@Entity
class TestBadge {

    @Id
    private UUID id;

    private String serial;

    @OneToOne(mappedBy = "badge", fetch = FetchType.LAZY)
    private TestBadgeHolder holder;

    TestBadge() {
    }

    TestBadge(String serial) {
        this.serial = serial;
    }

    UUID getId() {
        return id;
    }

    String getSerial() {
        return serial;
    }

    TestBadgeHolder getHolder() {
        return holder;
    }

    void setHolder(TestBadgeHolder holder) {
        this.holder = holder;
    }
}
