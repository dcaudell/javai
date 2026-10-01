package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/** The owning side of {@link TestDepartment}'s bidirectional pair -- this table holds the foreign key. */
@Entity
final class TestEmployee {

    @Id
    private UUID id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    private TestDepartment department;

    TestEmployee() {
    }

    TestEmployee(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    TestDepartment getDepartment() {
        return department;
    }

    void setDepartment(TestDepartment department) {
        this.department = department;
    }
}
