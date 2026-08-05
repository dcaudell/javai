package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * A {@code @Version}-bearing entity with no vectorization whatsoever (OMI-254).
 *
 * <p>Optimistic locking is orthogonal to embedding, so it has to work for an entity that has nothing to
 * embed -- and this is the case the vector machinery cannot carry by accident: the walks that move vector
 * state between the caller's graph and Hibernate's managed copy only ever descend into
 * {@code JavAIVectorizable} nodes, and would not find this entity or any field on it.
 */
@Entity
final class TestVersionedCounter {

    @Id
    private UUID id;

    @Version
    private long version;

    private int tally;

    TestVersionedCounter() {
    }

    TestVersionedCounter(int tally) {
        this.id = UUID.randomUUID();
        this.tally = tally;
    }

    UUID getId() {
        return id;
    }

    long getVersion() {
        return version;
    }

    int getTally() {
        return tally;
    }

    void setTally(int tally) {
        this.tally = tally;
    }
}
