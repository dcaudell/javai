package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.Taggregate;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic pins for the {@code concatenate} opt-in -- all {@code TaggregateReflection} still answers.
 *
 * <p>Its field-walking half is gone (OMI-304): membership comes from {@code Containment} now, read from the
 * mapping rather than from a loaded object graph, so the cases this class used to cover (collection fields,
 * inherited declarations, null handling, non-{@code Taggable} members) are covered against a real database
 * by {@code JavAITaggregatePostgresE2ETest} instead -- which is the only place they can honestly be tested,
 * since the answer now depends on what is stored rather than on what an object holds.
 */
class TaggregateReflectionTest {

    @Taggregate(concatenate = true)
    static class Concatenating implements Taggable {
        @Id
        UUID id = UUID.randomUUID();
    }

    /** A container that aggregates but never opted into tag text -- the two are independent opt-ins. */
    static class AggregatingOnly implements Taggable {
        @Id
        UUID id = UUID.randomUUID();

        @Taggregate
        Taggable member;
    }

    /** {@code concatenate} on a FIELD placement is meaningless by design (the deliberate asymmetry against
     *  {@code @Summary}) and must not leak into the type-level opt-in. */
    static class FieldConcatenateOnly implements Taggable {
        @Id
        UUID id = UUID.randomUUID();

        @Taggregate(concatenate = true)
        Taggable child;
    }

    static class DeclaresNothing implements Taggable {
        @Id
        UUID id = UUID.randomUUID();
    }

    @Test
    void concatenateIsReadFromTheTypePlacementOnly() {
        assertTrue(TaggregateReflection.concatenates(Concatenating.class));
        assertFalse(TaggregateReflection.concatenates(AggregatingOnly.class),
                "aggregating is not opting into tag text");
        assertFalse(TaggregateReflection.concatenates(FieldConcatenateOnly.class),
                "a FIELD placement's concatenate flag must not opt the type in");
        assertFalse(TaggregateReflection.concatenates(DeclaresNothing.class));
    }
}
