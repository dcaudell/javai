package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Taggregate;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

import java.util.UUID;

/**
 * A container whose member field is {@code @Taggregate} -- the third kind of derived state a bulk update must
 * not be allowed to move (OMI-406).
 *
 * <p>Deliberately not a tagging fixture: {@code javai-persistence} does not depend on {@code javai-tagging},
 * and the guard being tested keys on the annotation, which lives in {@code javai-annotations} alongside
 * {@code @Summary} and {@code @Vectorize}. What matters here is that the refusal covers all three kinds of
 * derivation rather than only the two this module can otherwise see.
 */
@Entity
final class TestTaggregateOwner {

    @Id
    private UUID id;

    private String name;

    @Taggregate
    @OneToOne(cascade = CascadeType.ALL)
    private TestCounterRow member;

    TestTaggregateOwner() {
    }

    TestTaggregateOwner(String name, TestCounterRow member) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.member = member;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    TestCounterRow getMember() {
        return member;
    }
}
