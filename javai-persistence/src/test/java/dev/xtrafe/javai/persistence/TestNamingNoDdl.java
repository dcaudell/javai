package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** Reserved for the test that turns schema export off via the Hibernate-property passthrough: its table
 *  must never be created by any other test in the suite, or the assertion proves nothing. */
@Entity
final class TestNamingNoDdl {

    @Id
    private UUID id;

    private String emailAddress;

    TestNamingNoDdl() {
    }

    TestNamingNoDdl(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    UUID getId() {
        return id;
    }
}
