package dev.xtrafe.javai.tagging;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/**
 * Holds a {@link TestThing} through a {@code LAZY} singular association, purely so a test can obtain a real
 * Hibernate proxy for a {@code @Taggable} entity.
 *
 * <p>That is the shape that made {@code refOf}'s proxy handling worth fixing: a caller who reaches a
 * taggable through an association -- rather than loading it from its own repository -- holds a generated
 * subclass whose {@code getClass().getName()} and whose {@code @Id} field both answer wrongly, silently.
 * {@link TestThing} is deliberately not {@code final}, since Hibernate proxies by subclassing and a final
 * entity is quietly eager (OMI-279).
 */
@Entity
public class ThingOwner {

    @Id
    private UUID id;

    private String label;

    @ManyToOne(fetch = FetchType.LAZY)
    private TestThing thing;

    public ThingOwner() {
    }

    public ThingOwner(String label, TestThing thing) {
        this.id = UUID.randomUUID();
        this.label = label;
        this.thing = thing;
    }

    public UUID getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    /** ⚠️ Returns an uninitialized proxy when this owner was loaded lazily -- which is the point. */
    public TestThing getThing() {
        return thing;
    }
}
