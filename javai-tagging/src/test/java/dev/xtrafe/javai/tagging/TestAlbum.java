package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;

import java.util.UUID;

/**
 * A plain-{@code Taggable} Taggregate container -- deliberately NOT {@code @JavAIVectorizable}, pinning the
 * lineage rule from ordinary client code: a container needs the marker interface and an {@code @Id UUID},
 * never weaving.
 *
 * <p><b>A real {@code @Entity} with real associations (OMI-304), where it used to be an unpersisted
 * object.</b> That is the change the ticket is about: membership is no longer a snapshot the library keeps
 * on the side, it is the join tables Hibernate already maintains, so a container has to be a persisted
 * entity for its members to be discoverable at all. The collections are {@code LAZY}, which is the normal
 * shape and used to be fatal -- reconciling walked them and threw {@code LazyInitializationException} on
 * any entity read outside a session. Nothing walks them now.
 *
 * <p>Both {@code @Taggregate} placements are covered: the singular reference ({@link #cover}) and the
 * collection ({@link #things}). {@code @Taggregate(concatenate = true)} at type level opts it into the
 * tag-text vector.
 */
@Entity
@dev.xtrafe.javai.annotations.Taggable
@Taggregate(concatenate = true)
public class TestAlbum implements Taggable {

    @Id
    private UUID id;

    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @Taggregate
    private TestThing cover;

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_album_things")
    @Taggregate
    private JavAIList<TestThing> things = new JavAIArrayList<>();

    /** Nested containment: an album of albums, so one fixture covers taggregate-of-taggregate. */
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.LAZY)
    @JoinTable(name = "test_album_albums")
    @Taggregate
    private JavAIList<TestAlbum> albums = new JavAIArrayList<>();

    public TestAlbum() {
    }

    public TestAlbum(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public JavAIList<TestThing> getThings() {
        return things;
    }

    public JavAIList<TestAlbum> getAlbums() {
        return albums;
    }

    public void setCover(TestThing cover) {
        this.cover = cover;
    }
}
