package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIArrayList;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.UUID;

/** Deliberately invalid on Postgres: a JPA association annotation on a <em>concrete</em>-typed JavAI
 *  collection field, whose cascade/ownership semantics the side-table mapping would silently ignore. Must
 *  be rejected at registration time, with a message pointing at the interface-typed declaration that makes
 *  it a native Hibernate association instead (OMI-142). Contrast {@code TestCrew}, which declares the
 *  supported shape. */
@Entity
final class TestAnnotatedJavAICollection {

    @Id
    private UUID id;

    @OneToMany
    private final JavAIArrayList<TestMember> members = new JavAIArrayList<>();

    TestAnnotatedJavAICollection() {
    }

    UUID getId() {
        return id;
    }
}
