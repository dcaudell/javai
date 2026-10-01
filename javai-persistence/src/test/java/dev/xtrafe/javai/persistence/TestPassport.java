package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;

import java.util.UUID;

/**
 * {@code @MapsId} -- a child whose primary key <em>is</em> its parent's, rather than one of its own.
 *
 * <p>The reason this is worth measuring: JavAI's identity contract is an application-assigned {@code UUID},
 * and {@code save()} assigns a random one to any {@code @Id} it finds null before handing the graph to
 * Hibernate. {@code @MapsId} says the id must instead be copied from the association. Those two rules are in
 * direct tension, and which one wins is not something to reason about from the source.
 */
@Entity
final class TestPassport {

    @Id
    private UUID id;

    @MapsId
    @OneToOne(cascade = CascadeType.ALL)
    private TestVehicle vehicle;

    private String serial;

    TestPassport() {
    }

    TestPassport(TestVehicle vehicle, String serial) {
        this.vehicle = vehicle;
        this.serial = serial;
    }

    UUID getId() {
        return id;
    }

    TestVehicle getVehicle() {
        return vehicle;
    }

    String getSerial() {
        return serial;
    }
}
