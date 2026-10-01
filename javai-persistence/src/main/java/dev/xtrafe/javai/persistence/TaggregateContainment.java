package dev.xtrafe.javai.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The narrow view of this module's {@code Containment} that {@code javai-tagging} consumes (OMI-304) --
 * "which containers hold this member", "which members does this container hold", and the ambient
 * transaction to write on, expressed in the {@code (fully-qualified type name, UUID)} vocabulary
 * {@code TaggableRef} already speaks.
 *
 * <p><b>A view, never a second implementation.</b> Every method here delegates to the one
 * {@code Containment} instance the backend already resolved for {@code @Summary} recomputation, so
 * parents-from-child is answered by exactly one piece of code no matter which annotation asked. Taggregate
 * used to keep its own {@code javai_taggregate_members} snapshot -- a second, staler copy of what the join
 * tables already hold, which could not answer for a container nothing had reconciled yet. That table is
 * gone, and this is what replaced it.
 *
 * <p>Deliberately a hand-rolled contract in {@code (String, UUID)} rather than the promotion of
 * {@code Containment}/{@code Edge}/{@code OwnerRef}/{@code Kind} to public: this module is published, and
 * four permanently-exposed internal types is a larger promise than one narrow interface. Obtain it from
 * {@link JavAIPI#taggregateContainment(JavAIPersistenceConfig)}.
 */
public interface TaggregateContainment {

    /** Whether the registered model declares no {@code @Taggregate} field at all -- lets a caller skip the
     *  machinery entirely, and is what keeps a model that uses none paying nothing for it. */
    boolean isEmpty();

    /**
     * Feeds every container currently holding {@code (childTypeName, childId)} through a
     * {@code @Taggregate} field to {@code sink}, as {@code (containerTypeName, containerId)}.
     *
     * <p>Read from the stored relationships, so it is true regardless of what any session has loaded -- a
     * pod that never held the container still finds it, and a container that has never been reconciled is
     * found the first time one of its members is tagged.
     */
    void containersOf(String childTypeName, UUID childId, BiConsumer<String, UUID> sink);

    /** Feeds every member currently held by {@code (containerTypeName, containerId)} through its
     *  {@code @Taggregate} fields to {@code sink}. The forward direction, and what lets a recompute be a
     *  query rather than a walk of a loaded object's lazy collections. */
    void membersOf(String containerTypeName, UUID containerId, BiConsumer<String, UUID> sink);

    /** Feeds every persisted container of every {@code @Taggregate}-declaring type to {@code sink} -- the
     *  work list for a full repair, which cannot be driven by a pending set that direct SQL never wrote. */
    void allContainers(BiConsumer<String, UUID> sink);

    /**
     * Runs {@code work} on the ambient transaction's own JDBC connection when the caller is inside one
     * ({@code JavAIPI.inTransaction}, or a Spring {@code @Transactional} method), and returns {@code true}.
     * Returns {@code false} without running anything when there is no ambient transaction, leaving the
     * caller to use its own connection.
     *
     * <p>This is what lets a tag mutation and the pending row it enqueues commit or roll back together: on
     * its own autocommit connection the tag row would survive a caller's rollback while the derived rows
     * were computed from it.
     */
    boolean inAmbientTransaction(ConnectionWork work) throws SQLException;

    /**
     * Registers {@code drain} to run once the caller's transaction has actually committed, returning
     * {@code true}; returns {@code false} when there is no ambient transaction, leaving the caller to run
     * it inline because its write is already committed.
     *
     * <p>The same discipline {@code @Summary} recomputation follows, and for the same two reasons: work
     * done before the commit reads a state no other connection can see, and a transaction that rolls back
     * must leave no derived rows behind. Registered once per transaction rather than once per mutation, so
     * a loop tagging two hundred members drains once at the end rather than two hundred times.
     */
    boolean afterCommit(Runnable drain);

    /** JDBC work that runs on a connection this module owns. Separate from a general {@code SQLConsumer}
     *  on purpose: nothing else may run here. */
    @FunctionalInterface
    interface ConnectionWork {
        void run(Connection connection) throws SQLException;
    }

    /** The containment of a model that declares no {@code @Taggregate} field -- what a caller holding no
     *  {@link JavAIPersistenceConfig} (a hand-constructed backend in a test) gets, so the Taggregate paths
     *  stay inert rather than needing a null check at every call site. */
    TaggregateContainment NONE = new TaggregateContainment() {
        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public void containersOf(String childTypeName, UUID childId, BiConsumer<String, UUID> sink) {
        }

        @Override
        public void membersOf(String containerTypeName, UUID containerId, BiConsumer<String, UUID> sink) {
        }

        @Override
        public void allContainers(BiConsumer<String, UUID> sink) {
        }

        @Override
        public boolean inAmbientTransaction(ConnectionWork work) {
            return false;
        }

        @Override
        public boolean afterCommit(Runnable drain) {
            return false;
        }
    };
}
