package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A PLAIN (non-JavAI) {@code @OneToMany} collection -- an ordinary JPA association Hibernate owns end to
 * end. The field is deliberately NOT {@code final} (Hibernate substitutes its own {@code PersistentBag},
 * unlike a JavAI collection field, which JavAI hydrates <em>into</em> and therefore can be final).
 *
 * <p>Before OMI-142's fix this shape was silently broken: the field was mapped natively by Hibernate AND
 * claimed by {@code javai_collection_members}, so every element came back doubled on read.
 */
@Entity
final class TestTeam {

    @Id
    private UUID id;

    private String name;

    @OneToMany(cascade = CascadeType.ALL)
    private List<TestMember> members = new ArrayList<>();

    TestTeam() {
    }

    TestTeam(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    List<TestMember> getMembers() {
        return members;
    }
}
