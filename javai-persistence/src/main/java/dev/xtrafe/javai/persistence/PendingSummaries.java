package dev.xtrafe.javai.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The durable record of which containers owe a summary recomputation -- {@code javai_summary_pending}
 * (OMI-255).
 *
 * <h2>Why a table, and why insert-only</h2>
 *
 * The whole difficulty this solves is that a container's summary is <b>one row per owner</b>, so two writers
 * anywhere beneath one container write the same row and refuse each other at {@code REPEATABLE READ}. An
 * append-only queue inverts that: every enqueue is an {@code INSERT} of a row with its own fresh primary
 * key, so two writers touching the same container produce two different rows and cannot conflict at any
 * isolation level. The shared row is then written exactly once, later, by a single drain holding a lock.
 *
 * <p>It is a table rather than an in-memory queue for the reason the whole ticket exists: the deployment is
 * multi-pod. Work parked in one JVM's queue is invisible to the other pods and lost outright if that pod
 * dies mid-request. A row survives both, and any pod can drain it.
 *
 * <p><b>Duplicates are expected and are not a problem.</b> Ten writes to one container enqueue ten rows; the
 * drain collapses them by owner and recomputes once. Deduplicating at enqueue time would mean reading before
 * writing -- reintroducing exactly the shared-row contention this exists to avoid.
 */
final class PendingSummaries {

    static final String TABLE = "javai_summary_pending";

    private PendingSummaries() {
    }

    static void createTable(java.sql.Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id          uuid         NOT NULL,"
                + "owner_type  varchar(255) NOT NULL,"
                + "owner_id    uuid         NOT NULL,"
                + "enqueued_at timestamptz  NOT NULL,"
                + "PRIMARY KEY (id))");
        statement.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_owner ON " + TABLE
                + " (owner_type, owner_id)");
        // Drains claim by age, oldest first, so that a container being written to constantly cannot starve
        // one that was written to once and then left alone.
        statement.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_enqueued ON " + TABLE + " (enqueued_at)");
    }

    /**
     * Records that {@code owner}'s summary is owed a recomputation. Runs on the caller's own connection, so
     * it commits or rolls back exactly with the mutation that caused it -- a write that is rolled back must
     * not leave a recomputation queued for something that never happened.
     */
    static void enqueue(Connection connection, Containment.OwnerRef owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (id, owner_type, owner_id, enqueued_at) VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, owner.ownerType().getName());
            statement.setObject(3, owner.ownerId());
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    /** One claimed batch: the distinct owners to recompute, and the exact rows that named them. Keeping the
     *  row ids is what makes the delete safe -- anything enqueued <em>after</em> this claim describes a
     *  mutation this drain's recomputation may not have seen, and must survive for the next drain. */
    record Claim(List<PendingOwner> owners, List<UUID> rowIds) {
        boolean isEmpty() {
            return owners.isEmpty();
        }
    }

    /** An owner named by the queue. The type is carried as a name because a row can outlive a rename or a
     *  class that is no longer on this pod's classpath -- see {@code resolveOwnerType}. */
    record PendingOwner(String ownerTypeName, UUID ownerId) {
    }

    /** Claims up to {@code limit} distinct owners, oldest first. Reads only -- the rows stay until
     *  {@link #delete} confirms their work is done, so a drain that dies loses nothing. */
    static Claim claim(Connection connection, int limit) throws SQLException {
        Set<PendingOwner> owners = new LinkedHashSet<>();
        List<UUID> rowIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, owner_type, owner_id FROM " + TABLE + " ORDER BY enqueued_at LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    rowIds.add((UUID) rows.getObject("id"));
                    owners.add(new PendingOwner(rows.getString("owner_type"), (UUID) rows.getObject("owner_id")));
                }
            }
        }
        return new Claim(List.copyOf(owners), rowIds);
    }

    /** Claims only the rows naming {@code owners} -- what a {@code save} drains when it wants its own write
     *  reflected before returning, without taking on the whole backlog of every other container. */
    static Claim claimFor(Connection connection, Set<Containment.OwnerRef> owners) throws SQLException {
        List<PendingOwner> claimed = new ArrayList<>();
        List<UUID> rowIds = new ArrayList<>();
        for (Containment.OwnerRef owner : owners) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM " + TABLE + " WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, owner.ownerType().getName());
                statement.setObject(2, owner.ownerId());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        rowIds.add((UUID) rows.getObject(1));
                    }
                }
            }
            claimed.add(new PendingOwner(owner.ownerType().getName(), owner.ownerId()));
        }
        return new Claim(List.copyOf(claimed), rowIds);
    }

    /**
     * Removes exactly the rows a completed drain claimed, by id.
     *
     * <p>By id, never by owner: a mutation committed while this drain was running enqueues a new row for the
     * same owner, and that row describes a change the finished recomputation may not have included. Deleting
     * by owner would discard it and leave the summary permanently one write behind -- the kind of loss that
     * never surfaces as an error.
     */
    static void delete(Connection connection, List<UUID> rowIds) throws SQLException {
        if (rowIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE id = ANY (?)")) {
            statement.setArray(1, connection.createArrayOf("uuid", rowIds.toArray()));
            statement.executeUpdate();
        }
    }
}
