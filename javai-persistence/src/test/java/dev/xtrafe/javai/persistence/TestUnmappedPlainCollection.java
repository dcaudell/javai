package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Deliberately invalid on Postgres: a plain JDK collection with no JPA mapping annotation, which Hibernate
 *  cannot map. Must be rejected at registration time with a clearer message than Hibernate's own boot error. */
@Entity
final class TestUnmappedPlainCollection {

    @Id
    private UUID id;

    private List<TestMember> items = new ArrayList<>();

    TestUnmappedPlainCollection() {
    }

    UUID getId() {
        return id;
    }

    List<TestMember> getItems() {
        return items;
    }
}
