package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Marks a {@link Query} as a write -- an {@code update} or {@code delete} executed directly, rather than a
 * select (OMI-398).
 *
 * <p>Its reason for existing is that {@code save(entity)} persists the <em>whole row</em>, which makes a
 * single-column write inexpressible and therefore makes any column maintained outside its entity's own
 * editing path clobberable: load a row before some counter moved, edit an unrelated field, save, and the
 * stale counter goes back with it. A repository handing back detached entities is exactly the shape that goes
 * stale. This is also the only way to get an <em>atomic</em> read-modify-write:
 *
 * <pre>{@code
 * @Modifying
 * @Query("update MediaSocialDetails d set d.likeCount = d.likeCount + 1 where d.id = :id")
 * int incrementLikeCount(@Param("id") UUID id);
 * }</pre>
 *
 * <h2>Keeping a column off {@code save()}'s path needs nothing new</h2>
 *
 * JPA's own {@code @Column(updatable = false)} already means "ordinary entity updates never write this",
 * while a query like the one above writes it anyway -- measured, not assumed. So a column maintained by a
 * dedicated path is declared with the JPA annotation and written through a method like this one; JavAI adds
 * no marker of its own for it.
 *
 * <h2>What such a write must not touch, and why it is refused rather than documented</h2>
 *
 * A bulk update fires no woven accessor, so Vector Core is never told the value changed -- the mutation rule
 * in {@code SPEC.md}. Persistence widens that from a process-lifetime staleness into a durable one, because a
 * stored vector is read straight back into a loaded object's slots. A statement assigning to any of these is
 * therefore refused when the repository is realized:
 *
 * <ul>
 *   <li>a {@code @Vectorize} field or an {@code @ExternalVector}'s key -- the vector would go stale and stay
 *       stale, in the database, for every later load;</li>
 *   <li>a {@code @Summary} field -- reassigning one changes containment, so the summary vectors of both the
 *       old and the new container are wrong with nothing queued to fix them;</li>
 *   <li>a {@code @Taggregate} field -- the same drift one layer up, invisible to tagging's own reconciliation.</li>
 * </ul>
 *
 * An ordinary column on a vectorized entity is fine and stays allowed: a summary is arithmetic over vectors,
 * so a column no vector reads cannot move one.
 *
 * <p><b>{@code nativeQuery = true} is refused outright on a {@code @JavAIVectorizable} entity's repository.</b>
 * The check above reads the parsed statement's assignments, and a native statement has none to read -- so the
 * repository it is declared on is the only signal available. Crude, and deliberately so: the alternative is
 * an unenforceable rule in a document.
 *
 * <h2>Delete</h2>
 *
 * A {@code delete} resolves the matching ids and then removes each entity through the same path
 * {@code deleteById} uses, rather than issuing one bulk statement. A bulk delete cascades to nothing, detaches
 * from no container (so a join table's foreign key refuses it outright), and leaves JavAI's own vector and geo
 * rows orphaned -- which is why the derived {@code deleteBy…} finders already work this way.
 *
 * <h2>Two things to know</h2>
 *
 * A bulk update does not increment {@code @Version} unless the query says {@code update versioned}. And it
 * bypasses the persistence context, so entities already loaded in the current unit of work keep their old
 * values in memory -- see {@link #clearAutomatically()}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Modifying {

    /**
     * Whether to flush the persistence context before the statement runs.
     *
     * <p>Matters when the same unit of work has already changed something the statement's own {@code where}
     * clause selects on: unflushed, those changes are still in memory and the database cannot see them.
     * Default {@code false} -- flushing writes out every pending change in the session, not only the relevant
     * one, which is a larger effect than most callers of a targeted write intend.
     */
    boolean flushAutomatically() default false;

    /**
     * Whether to clear the persistence context after the statement runs.
     *
     * <p>The statement writes rows, not managed objects, so an entity already loaded in this unit of work
     * keeps the value it had -- and saving it afterwards would write that stale value back. Clearing detaches
     * everything so the next read comes from the database. Default {@code false}, since clearing also detaches
     * entities the caller is still holding, and a repository whose reads are already detached usually has
     * nothing loaded to go stale.
     */
    boolean clearAutomatically() default false;
}
