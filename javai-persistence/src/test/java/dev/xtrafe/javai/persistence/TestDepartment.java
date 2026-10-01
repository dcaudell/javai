package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A bidirectional {@code @OneToMany}/{@code @ManyToOne} <b>pair</b> -- both ends of one relationship,
 * mapped by each other.
 *
 * <p>OMI-275 measured each end's shape separately and never a pair, so nothing said the two ends agree:
 * that the child reached from the parent and the parent reached back from that child are the same objects,
 * and share one attachment state. On a bidirectional mapping that is a real question rather than a
 * tautology -- the two ends are two mappings over one foreign key, and only one of them owns it.
 */
@Entity
final class TestDepartment {

    @Id
    private UUID id;

    private String name;

    @OneToMany(mappedBy = "department", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TestEmployee> employees = new ArrayList<>();

    TestDepartment() {
    }

    TestDepartment(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    List<TestEmployee> getEmployees() {
        return employees;
    }

    /** Sets both ends, which a bidirectional mapping requires of the application -- Hibernate maintains the
     *  foreign key from the owning side only. */
    void hire(TestEmployee employee) {
        employees.add(employee);
        employee.setDepartment(this);
    }
}
