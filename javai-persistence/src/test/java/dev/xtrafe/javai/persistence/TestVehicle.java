package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;

import java.util.UUID;

/**
 * The root of a JPA inheritance hierarchy, {@code JOINED} -- the strategy whose fetching is worth measuring,
 * since reading a subclass instance means a join and reading the root polymorphically means several.
 *
 * <p>{@code SINGLE_TABLE} would prove less: one table, no join, nothing for a fetch strategy to get wrong.
 * JavAI has no inheritance handling of its own, so what is being checked is that it does not get in the way
 * of Hibernate's -- registration accepts the hierarchy, and a subclass round-trips as itself.
 */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
class TestVehicle {

    @Id
    private UUID id;

    private String label;

    TestVehicle() {
    }

    TestVehicle(String label) {
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }
}
