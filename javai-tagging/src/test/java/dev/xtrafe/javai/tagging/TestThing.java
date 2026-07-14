package dev.xtrafe.javai.tagging;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** A minimal, plain {@code @Taggable} entity -- not {@code @JavAIVectorizable}, deliberately, to prove
 *  tagging works on an object with no embedding of its own (see doc/spec/tagging.md's "Orthogonality"). */
@Entity
@dev.xtrafe.javai.annotations.Taggable
public class TestThing implements Taggable {

    @Id
    private UUID id;

    private String name;

    public TestThing() {
    }

    public TestThing(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
