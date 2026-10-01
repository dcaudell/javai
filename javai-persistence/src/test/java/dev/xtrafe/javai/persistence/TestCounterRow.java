package dev.xtrafe.javai.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * A plain, non-vectorized {@code @Entity} carrying the two shapes OMI-398 is about: a column an ordinary
 * {@code save()} may write ({@code votes}), and one it may not ({@code tally}).
 *
 * <p>{@code tally} is the ticket's motivating case -- a count maintained by its own path, after some event
 * the entity's editing path knows nothing about. Loading such a row before the count moves, editing an
 * unrelated field and saving would otherwise write the stale count back, which a repository handing out
 * detached entities makes easy to do by accident. {@code @Column(updatable = false)} is what stops it, and
 * a {@code @Modifying} query is what still writes it.
 */
@Entity
final class TestCounterRow {

    @Id
    private UUID id;

    private String label;

    private int votes;

    /** Read-only to {@code save()}, writable through a targeted {@code @Modifying} query -- measured, not
     *  assumed: see {@code ModifyingQueryTest}. */
    @Column(updatable = false)
    private long tally;

    TestCounterRow() {
    }

    TestCounterRow(String label, int votes, long tally) {
        this.id = UUID.randomUUID();
        this.label = label;
        this.votes = votes;
        this.tally = tally;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    void setLabel(String label) {
        this.label = label;
    }

    int getVotes() {
        return votes;
    }

    void setVotes(int votes) {
        this.votes = votes;
    }

    long getTally() {
        return tally;
    }

    void setTally(long tally) {
        this.tally = tally;
    }
}
