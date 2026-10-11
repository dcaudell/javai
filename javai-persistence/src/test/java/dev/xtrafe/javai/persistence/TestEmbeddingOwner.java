package dev.xtrafe.javai.persistence;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** Holds a {@link TestEmbeddedPart}; its registration is refused (OMI-623). Keep it named by its own test only. */
@Entity
final class TestEmbeddingOwner {

    @Id
    private UUID id;

    @Embedded
    private TestEmbeddedPart part;

    TestEmbeddingOwner() {
    }
}
