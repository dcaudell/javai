package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.Taggregate;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * A second tag-text opt-in, structurally identical to {@link TestTextedThing} and differing only in its
 * type -- which is the entire point (OMI-460): the tag indexes span every {@code @Taggable} type at once,
 * so proving that a search can be narrowed to one of them needs two of them in the same index.
 *
 * <p>Deliberately not {@link TestAlbum}, whose lazy {@code @ManyToMany} Taggregate collections exist to
 * exercise containment and would only add mapping surface to a test about narrowing.
 */
@Entity
@dev.xtrafe.javai.annotations.Taggable
@Taggregate(concatenate = true)
public class TestTextedNote implements Taggable {

    @Id
    private UUID id;

    private String name;

    public TestTextedNote() {
    }

    public TestTextedNote(String name) {
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
