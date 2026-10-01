package dev.xtrafe.javai.persistence;

import jakarta.persistence.Basic;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.util.UUID;

/**
 * {@code @Basic(fetch = LAZY)} on a large column -- JPA's way of saying "do not load this unless asked".
 *
 * <p>Unlike a lazy <em>association</em>, a lazy basic attribute has no proxy to stand in for it: Hibernate
 * can only defer it by rewriting field access, which needs build-time or agent bytecode enhancement. Whether
 * that enhancement is active in a JavAI-built {@code SessionFactory} is what {@code JpaMappingConformanceTest}
 * measures rather than assumes.
 */
@Entity
final class TestLazyBasicOwner {

    @Id
    private UUID id;

    private String label;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    private String bulk;

    TestLazyBasicOwner() {
    }

    TestLazyBasicOwner(String label, String bulk) {
        this.label = label;
        this.bulk = bulk;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    String getBulk() {
        return bulk;
    }
}
