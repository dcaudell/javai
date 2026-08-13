package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * The woven counterpart to {@link TestAlbum}: a genuinely {@code @JavAIVectorizable} Taggregate container,
 * transformed by the same build-time weaver that weaves {@link Tag}/{@link TagSet} (see this module's pom,
 * the {@code weave-test-fixtures} execution). Exists to pin the other half of the heterogeneous-graph
 * criterion -- a woven container aggregating plain members -- and that weaving neither enables nor breaks
 * anything Taggregate does, since the aggregate machinery is repository-side reflection either way.
 */
@JavAIVectorizable
@dev.xtrafe.javai.annotations.Taggable
public class TestWovenAlbum implements Taggable {

    @Id
    private UUID id;

    @Vectorize
    private String title;

    @Taggregate
    private final JavAIArrayList<Taggable> members = new JavAIArrayList<>();

    public TestWovenAlbum(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public JavAIArrayList<Taggable> getMembers() {
        return members;
    }
}
