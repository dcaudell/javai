package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.Taggregate;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * A non-aggregating tag-text opt-in: {@code @Taggregate(concatenate = true)} at TYPE placement alone, no
 * fields marked -- the spec's own "a {@code MediaImage} with its machine tags" shape. Persisted (unlike
 * {@link TestAlbum}) because the Neo4j backend writes the tag-text vector as properties on the entity's
 * own node, so the node must exist -- and that store/serve path is exactly what the Neo4j/Mongo e2e tests
 * pin with this fixture.
 */
@Entity
@dev.xtrafe.javai.annotations.Taggable
@Taggregate(concatenate = true)
public class TestTextedThing implements Taggable {

    @Id
    private UUID id;

    private String name;

    public TestTextedThing() {
    }

    public TestTextedThing(String name) {
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
