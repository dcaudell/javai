package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

/** Camel-cased field names, so the physical naming strategy in force is visible in the generated DDL --
 *  see {@link PhysicalNamingStrategyTest}. Plain {@code @Entity}, not {@code @JavAIVectorizable}: naming is
 *  orthogonal to vectorization, and a non-vectorized entity is a supported first-class case (OMI-138). */
@Entity
final class TestNamingCamel {

    @Id
    private UUID id;

    private String emailAddress;

    private boolean emailVerified;

    private String googleSubjectId;

    private Instant createdAt;

    TestNamingCamel() {
    }

    TestNamingCamel(String emailAddress) {
        this.emailAddress = emailAddress;
        this.createdAt = Instant.now();
    }

    UUID getId() {
        return id;
    }
}
