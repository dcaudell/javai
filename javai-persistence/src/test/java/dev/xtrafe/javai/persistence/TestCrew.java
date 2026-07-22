package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.UUID;

/**
 * The OMI-142 Phase 2 target shape, and the whole point of the exercise: <b>nothing JavAI-specific is
 * written here.</b> Just a plain JPA {@code @OneToMany} whose field happens to be declared by a JavAI
 * collection interface. The backend attaches the JavAI collection type itself at mapping time, so Hibernate
 * owns the association (join table, cascade, lazy loading) while the instance it puts in the field is still
 * a real {@code JavAIList} with vectors and dirty-tracking.
 *
 * <p>Note the field is interface-typed and non-final -- required, because Hibernate substitutes its own
 * instance (the approved breaking change from the concrete {@code private final JavAIArrayList<...>} idiom).
 */
@Entity
final class TestCrew {

    @Id
    private UUID id;

    private String name;

    @OneToMany(cascade = CascadeType.ALL)
    private JavAIList<TestMember> members = new JavAIArrayList<>();

    TestCrew() {
    }

    TestCrew(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    JavAIList<TestMember> getMembers() {
        return members;
    }
}
