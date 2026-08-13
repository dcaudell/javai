package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.model.JavAIArrayList;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * A plain-{@code Taggable} Taggregate container -- deliberately NOT {@code @JavAIVectorizable}, and
 * deliberately never persisted: on Postgres every Taggregate store (taggings, membership snapshot, pending
 * set, both vector tables) is a dedicated table keyed by ref, so a container needs exactly what the lineage
 * rule says and nothing more -- the marker interface and an {@code @Id UUID}. Members are typed as bare
 * {@code Taggable} so one fixture pins heterogeneous graphs: woven members ({@link Tag}) and unwoven ones
 * ({@link TestThing}) mix freely.
 *
 * <p>{@code @Taggregate(concatenate = true)} at TYPE placement opts it into the tag-text vector; the two
 * FIELD placements cover both grammatical forms (single reference and JavAI collection).
 */
@dev.xtrafe.javai.annotations.Taggable
@Taggregate(concatenate = true)
public class TestAlbum implements Taggable {

    @Id
    private UUID id;

    private String title;

    @Taggregate
    private Taggable cover;

    @Taggregate
    private final JavAIArrayList<Taggable> members = new JavAIArrayList<>();

    public TestAlbum(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public JavAIArrayList<Taggable> getMembers() {
        return members;
    }

    public void setCover(Taggable cover) {
        this.cover = cover;
    }
}
