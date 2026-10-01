package dev.xtrafe.javai.persistence;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.util.UUID;

/** OMI-556: a {@code @MappedSuperclass} with no {@code Point}; {@link TestPlace} declares its own. */
@MappedSuperclass
abstract class TestPlaceBase {

    @Id
    private UUID id;

    private String name;

    TestPlaceBase() {
    }

    TestPlaceBase(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }
}
