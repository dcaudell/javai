package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;

/** A {@code JOINED} subclass of {@link TestVehicle}: its own table, joined to the root's on read. */
@Entity
final class TestTruck extends TestVehicle {

    private int axles;

    TestTruck() {
    }

    TestTruck(String label, int axles) {
        super(label);
        this.axles = axles;
    }

    int getAxles() {
        return axles;
    }
}
