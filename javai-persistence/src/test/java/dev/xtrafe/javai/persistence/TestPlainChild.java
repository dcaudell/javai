package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * The leaf of OMI-271's control graph: an entity with <em>nothing</em> JavAI-specific about it -- not
 * {@code JavAIVectorizable}, no {@code @Vectorize}, no {@code @Summary}, no JavAI collection, no
 * {@code Point}. See {@link TestPlainOwner} for what that is controlling for.
 */
@Entity
final class TestPlainChild {

    @Id
    private UUID id;

    private String name;

    TestPlainChild() {
    }

    TestPlainChild(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }
}
