package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

import java.util.UUID;

/**
 * A {@code @JavAIVectorizable} member of {@link TestTeam}'s <em>plain</em> (Hibernate-owned)
 * {@code @OneToMany} collection. Deliberately vectorized: it's what proves the vector bridge -- Hibernate's
 * own cascade INSERTs this entity, and JavAI must still force and persist its vector even though the
 * collection never touches {@code javai_collection_members}. See OMI-142.
 */
@Entity
final class TestMember implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String nickname;

    /** Makes this fixture reachable-at-depth: a {@code TestTeam}'s member owns a profile, so the profile sits
     *  TWO cascade hops from the saved root. The one-level reflective walk never reached it -- only the
     *  flush listener does. */
    @OneToOne(cascade = CascadeType.ALL)
    private TestProfile profile;

    TestMember() {
    }

    TestMember(String nickname) {
        this.nickname = nickname;
    }

    UUID getId() {
        return id;
    }

    String getNickname() {
        return nickname;
    }

    TestProfile getProfile() {
        return profile;
    }

    void setProfile(TestProfile profile) {
        this.profile = profile;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "nickname");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "nickname");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "nickname");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "nickname", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "nickname", reference);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type) {
        return JavAIRuntime.query(this, reference, type, Integer.MAX_VALUE);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth) {
        return JavAIRuntime.query(this, reference, type, maxDepth);
    }

    @Override
    public EmbeddingVector fieldVector(String fieldName) {
        return JavAIRuntime.fieldVector(this, fieldName);
    }
}
