package dev.xtrafe.javai.persistence;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.geo.Point;

import java.util.UUID;

/** OMI-556: a {@code @MappedSuperclass} that declares the {@code Point} its subclass inherits. */
@MappedSuperclass
abstract class TestLocatedPlaceBase {

    @Id
    private UUID id;

    private String name;

    private Point coordinates;

    TestLocatedPlaceBase() {
    }

    TestLocatedPlaceBase(String name, Point coordinates) {
        this.name = name;
        this.coordinates = coordinates;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    Point getCoordinates() {
        return coordinates;
    }
}
