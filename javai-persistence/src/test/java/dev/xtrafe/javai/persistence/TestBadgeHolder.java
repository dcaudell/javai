package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

import java.util.UUID;

/**
 * The <b>owning</b> side of a bidirectional {@code @OneToOne} ({@code badge}), plus a
 * {@code optional = false} one ({@code seal}) -- two cells OMI-275 named and never exercised.
 *
 * <p>{@code seal} is the interesting one. A non-optional to-one is the case where Hibernate cannot use a
 * proxy at all: a proxy would have to stand in for a row the mapping promises exists, and if it did not,
 * the caller would learn only on first dereference. So {@code LAZY} on a non-optional association is
 * classically ignored. Asked for here so the test can measure what is delivered.
 */
// ⚠️ NOT final: a lazy to-one pointing here (TestBadge.holder, the inverse side) can only be a proxy if
// this class can be subclassed. Left final, the inverse-side test would have measured finality and called
// it inverse-ness (OMI-279).
@Entity
class TestBadgeHolder {

    @Id
    private UUID id;

    private String name;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private TestBadge badge;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY, optional = false)
    private TestSeal seal;

    /** Identical mapping to {@code badge} in every respect except that its target class is {@code final}. */
    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private TestWaxSeal waxSeal;

    TestBadgeHolder() {
    }

    TestBadgeHolder(String name, TestBadge badge, TestSeal seal, TestWaxSeal waxSeal) {
        this.name = name;
        this.badge = badge;
        this.seal = seal;
        this.waxSeal = waxSeal;
        badge.setHolder(this);
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    TestBadge getBadge() {
        return badge;
    }

    TestSeal getSeal() {
        return seal;
    }

    TestWaxSeal getWaxSeal() {
        return waxSeal;
    }
}
