package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIArrayList;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * The shape OMI-277 stopped accepting: a <em>concrete</em>-typed JavAI collection field with <b>no</b>
 * association annotation.
 *
 * <p>This used to be a supported mapping, stored out-of-band in {@code javai_collection_members}. It is
 * refused at registration now because that storage was only ever read and written for the entity a
 * repository call returned -- reached through an association the collection came back silently empty, and
 * saved through one its members were silently never written. Neither failure announced itself.
 *
 * <p>Contrast {@link TestAnnotatedJavAICollection}, concrete <em>with</em> an annotation, which was already
 * refused, and {@link TestCrew}, which declares the supported shape.
 */
@Entity
final class TestConcreteJavAICollection {

    @Id
    private UUID id;

    private final JavAIArrayList<TestMember> members = new JavAIArrayList<>();

    TestConcreteJavAICollection() {
    }

    UUID getId() {
        return id;
    }
}
