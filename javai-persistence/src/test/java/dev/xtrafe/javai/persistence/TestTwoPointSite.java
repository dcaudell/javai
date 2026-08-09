package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.data.geo.Point;

import java.util.UUID;

/**
 * Two {@code Point} fields on one entity, which is the fixture that can tell one out-of-band read from
 * several (OMI-276).
 *
 * <p>Geo used to be read one field at a time -- a statement per {@code Point} per entity -- so an entity
 * with two of them cost twice what an entity with one did. A single entity cannot show that; two fields on
 * one entity can, and the scan count is the same either way if the reads are consolidated.
 */
@Entity
final class TestTwoPointSite {

    @Id
    private UUID id;

    private String name;

    private Point entrance;

    private Point exit;

    TestTwoPointSite() {
    }

    TestTwoPointSite(String name, Point entrance, Point exit) {
        this.name = name;
        this.entrance = entrance;
        this.exit = exit;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    Point getEntrance() {
        return entrance;
    }

    Point getExit() {
        return exit;
    }
}
