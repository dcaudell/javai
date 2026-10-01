package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;

import java.util.UUID;

/**
 * The woven counterpart to {@link TestAlbum}: a genuinely {@code @JavAIVectorizable} Taggregate container,
 * transformed by the same build-time weaver that weaves {@link Tag}/{@link TagSet}. Pins the other half of
 * the heterogeneous-lineage criterion -- a woven container aggregating plain members -- and that weaving
 * neither enables nor breaks anything Taggregate does, since containment is read from the mapping either
 * way.
 */
@Entity
@JavAIVectorizable
@dev.xtrafe.javai.annotations.Taggable
public class TestWovenAlbum implements Taggable {

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_woven_album_things")
    @Taggregate
    private JavAIList<TestThing> things = new JavAIArrayList<>();

    public TestWovenAlbum() {
    }

    public TestWovenAlbum(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public JavAIList<TestThing> getThings() {
        return things;
    }
}
