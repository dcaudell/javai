package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

import java.util.UUID;

/**
 * A plain, <b>non-{@code JavAIVectorizable}</b> {@code @Entity} -- the OMI-138 case for "an ordinary
 * relationally-queried entity, with no embedding of its own, served through the same
 * {@link JavAIRepository} as vectorized ones." It has only scalar fields plus a singular
 * {@code @OneToOne} to a vectorized {@link TestProfile}, and is queried purely through ordinary derived
 * finders ({@code findByUsername}, {@code findByAgeGreaterThanEqual}, {@code findByProfileHandle}, ...).
 * Proves both halves of the ticket at once: standard finders work, and a non-vectorized class is a
 * first-class citizen of the repository (its {@code save}/find path simply writes no vectors).
 */
@Entity
final class TestAccount {

    @Id
    private UUID id;

    private String username;

    private String email;

    private int age;

    private boolean active;

    @OneToOne(cascade = CascadeType.ALL)
    private TestProfile profile;

    TestAccount() {
    }

    TestAccount(String username, String email, int age, boolean active, TestProfile profile) {
        this.username = username;
        this.email = email;
        this.age = age;
        this.active = active;
        this.profile = profile;
    }

    UUID getId() {
        return id;
    }

    String getUsername() {
        return username;
    }

    String getEmail() {
        return email;
    }

    int getAge() {
        return age;
    }

    boolean isActive() {
        return active;
    }

    TestProfile getProfile() {
        return profile;
    }
}
