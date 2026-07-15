package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.PromptContext;
import dev.xtrafe.javai.annotations.TagIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** Not {@code @JavAIVectorizable} -- proves classification works on a plain {@code @Taggable} object with
 *  no embedding of its own (see doc/spec/tagging.md's "Orthogonality"). */
@Entity
@dev.xtrafe.javai.annotations.Taggable
public class TestClassifiableThing implements Taggable {

    @Id
    private UUID id;

    @PromptContext
    private String description;

    @TagIgnore
    @PromptContext
    private String internalNotes;

    public TestClassifiableThing() {
    }

    public TestClassifiableThing(String description, String internalNotes) {
        this.id = UUID.randomUUID();
        this.description = description;
        this.internalNotes = internalNotes;
    }

    public UUID getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public String getInternalNotes() {
        return internalNotes;
    }
}
