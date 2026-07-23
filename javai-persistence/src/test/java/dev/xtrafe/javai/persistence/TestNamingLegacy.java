package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** Same shape as {@link TestNamingCamel}, but reserved for the tests that pin Hibernate's bare-default
 *  naming ({@code emailverified}): one entity type can only own one table, so a type mapped under the
 *  legacy strategy must not also be mapped under the snake_case default in the same database. */
@Entity
final class TestNamingLegacy {

    @Id
    private UUID id;

    private String emailAddress;

    private boolean emailVerified;

    TestNamingLegacy() {
    }

    TestNamingLegacy(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    UUID getId() {
        return id;
    }
}
