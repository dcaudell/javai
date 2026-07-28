package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * An {@code @Entity} that <b>nothing</b> references: no field anywhere declares it, and no repository
 * interface names it. JavAI's field-walking discovery therefore cannot reach it by any route, which makes it
 * the fixture for {@code JavAIPersistenceConfig.Builder.entityType(...)} (OMI-212).
 *
 * <p>Keep it unreferenced. The moment any other fixture mentions it, it becomes discoverable and the escape
 * hatch test starts passing for the wrong reason.
 */
@Entity
final class TestOrphanEntity {

    @Id
    private UUID id;

    private String note;

    TestOrphanEntity() {
    }

    TestOrphanEntity(String note) {
        this.id = UUID.randomUUID();
        this.note = note;
    }

    UUID getId() {
        return id;
    }

    String getNote() {
        return note;
    }
}
