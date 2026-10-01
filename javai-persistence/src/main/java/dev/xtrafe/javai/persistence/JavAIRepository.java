package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring-Data-style repository base, per doc/spec/persistence-bridge.md. A repository interface extends
 * this with one type parameter -- {@code interface ArticleRepository extends JavAIRepository<Article>} --
 * and is realized via {@link JavAIPI#repository(Class, JavAIPersistenceConfig)}, never implemented by hand.
 *
 * <p>Identity is fixed to {@link UUID} for every persistable entity, recognized via a plain {@code @Id}
 * ({@code jakarta.persistence.Id}, already a transitive dependency through {@code hibernate-core}) field --
 * reused, not reinvented, specifically so the same annotation marks identity on both the Postgres and
 * Neo4j backends without requiring full JPA {@code @Entity} semantics on the Neo4j path.
 *
 * <p>Beyond this base CRUD contract, a repository interface may declare two kinds of derived query. First,
 * the vector convention {@code findNearestBy<Field>Vector(EmbeddingVector reference, int limit)} (or the
 * whole-object {@code findNearestByVector}/{@code findNearestBySummaryVector} variants) -- see {@link JavAIPI}'s
 * javadoc for the full naming rule. Second (OMI-138), ordinary Spring-Data-style relational finders --
 * {@code findBy<Field>}/{@code existsBy…}/{@code countBy…}/{@code deleteBy…} with the full {@code And}/{@code Or}
 * + operator + {@code OrderBy} + {@code Top}/{@code First} grammar, dynamic {@code Sort}/{@code Pageable}/
 * {@code Limit}, and {@code List}/{@code Optional}/single/{@code Stream}/{@code Page}/{@code Slice}/{@code long}/
 * {@code boolean} return adapters -- resolved against the entity's own mapped columns, so one repository serves
 * both an entity's relational access and its vector search (see {@link DerivedFinderQuery}). A non-vectorized
 * {@code @Entity} is served by exactly this same path. Either kind is validated, and anything matching neither
 * is rejected, at repository-creation time -- never on first call.
 */
public interface JavAIRepository<T> {

    /** Insert-or-update, auto-vectorized: every {@code @Vectorize}/{@code @Summary} vector is recomputed
     *  and persisted alongside the entity itself, in the same transaction where the backend supports one. */
    T save(T entity);

    /**
     * {@link #save(Object)} with an explicit choice of when the {@code @Summary} containers above this
     * entity are brought up to date (OMI-255).
     *
     * <p>{@code save(entity)} is exactly {@code save(entity, SummaryPolicy.RECOMPUTE_AFTER_COMMIT)} -- this
     * overload exists only to offer the other option, and changes nothing else about the write. The entity
     * and its own vectors are persisted identically either way.
     *
     * <p>Postgres is the only backend where the choice is meaningful in this phase; Neo4j and MongoDB
     * recompute inline and accept the argument without acting on it, rather than refusing a call whose
     * result would in fact be correct.
     *
     * @see SummaryPolicy
     */
    T save(T entity, SummaryPolicy summaryPolicy);

    /**
     * Saves several entities, embedding every vector they need in as few provider calls as the provider
     * supports (OMI-266).
     *
     * <p><b>Why this exists rather than a loop over {@link #save}.</b> A single {@code save} already batches
     * within its own reachable subgraph, but it cannot see past it: saving a hundred entities one call at a
     * time costs a hundred sequential round trips to the embedding provider, however well each one batches
     * internally. Measured on the Postgres backend, twelve two-field entities cost 24 texts in 24 round trips
     * saved individually, 24 texts in 1 round trip through this method. Nothing about what is written
     * changes -- each entity is persisted by exactly the same path, and simply finds its vectors already
     * computed.
     *
     * <p>The embeddings are computed <em>before</em> the write begins, so on Postgres they do not happen
     * inside the transaction at all.
     *
     * <p><b>Atomicity is Postgres-only, matching {@link JavAIPI#inTransaction}.</b> There the whole batch is
     * one transaction: either every entity is saved or none is. Neo4j and MongoDB write each entity
     * independently, so a failure part-way leaves the earlier entities saved -- they get the batching, which
     * is what this method is for, but no atomicity claim. This is stated rather than papered over, and it is
     * the same asymmetry {@code inTransaction} already carries; the difference is that refusing outright
     * would deny those backends the batching too, for a guarantee this method does not primarily exist to
     * provide.
     *
     * @param entities the entities to save; may be any {@link Iterable}, consumed once
     * @return the saved entities, in the order given -- each the same instance {@link #save} would return
     */
    List<T> saveAll(Iterable<T> entities);

    /** {@link #saveAll(Iterable)} with an explicit {@link SummaryPolicy}, exactly as {@link #save(Object, SummaryPolicy)}
     *  is to {@link #save(Object)}. */
    List<T> saveAll(Iterable<T> entities, SummaryPolicy summaryPolicy);

    Optional<T> findById(UUID id);

    List<T> findAll();

    /**
     * How many entities of this type the store holds (OMI-460).
     *
     * <p>The unconditional count. A predicate's count already had two routes -- a derived {@code countBy…}
     * finder and {@code @Query} -- and "how many are there" had none, so it was reached by
     * {@code findAll().size()}: every row hydrated into an entity, its vectors read back into its cache
     * slots, and the whole lot discarded to learn one number. Spring Data's {@code CrudRepository.count()}
     * is the precedent, and each backend answers it with the count its own store already knows how to do.
     */
    long count();

    void deleteById(UUID id);

    /**
     * <b>Re-indexes the whole datastore</b>, not just this repository's own type: every entity type
     * registered with the backing {@code JavAIPersistenceConfig} is re-embedded under the currently
     * configured model. Re-indexing one type in isolation would leave the store straddling two embedding
     * models -- an {@code Article} on the new one while its {@code Comment}s are still on the old -- which is
     * exactly the state a re-index exists to prevent, so the repository you happen to call this through
     * doesn't scope the work. The Postgres backend additionally <em>validates</em> the result afterwards,
     * throwing (and naming the offenders) if any entity that held a vector under the previous model didn't
     * get one under the new model.
     *
     * <p>Historically this re-embedded every existing entity of this type under whichever
     * {@code JavAIEmbeddingProvider}/model is *currently* configured -- the explicit trigger for
     * "I swapped providers, now go re-vectorize everything." Since both backends store each model's
     * vectors under a name qualified by that model (a per-model Postgres table; a per-model-qualified
     * Neo4j property) and {@link #save} always writes under the currently-configured model, this leaves
     * every *other* model's previously-written vectors completely untouched -- reverting the configured
     * provider back to one used before therefore needs no reindexing at all, since that model's data was
     * never overwritten in the first place.
     */
    void reindexAll();

    /**
     * Re-embeds and re-persists just <b>this</b> repository's own entity type, leaving every other type on
     * whatever model it was last written under -- the narrow counterpart to {@link #reindexAll()}, and the
     * behavior {@code reindexAll()} used to have before it was corrected to match its name.
     *
     * <p>Prefer {@link #reindexAll()} when swapping the configured embedding model: re-embedding one type in
     * isolation leaves the datastore straddling two models, which is usually a bug rather than an intent.
     * Reach for this only when that partial state is genuinely what you want -- e.g. re-embedding one type
     * after a targeted data repair. Because it is deliberately partial, it performs none of
     * {@code reindexAll()}'s completeness validation.
     */
    void reindex();

    // ---- externally-supplied vectors (OMI-290) -----------------------------------------------------

    /**
     * Stores a vector this process could not have computed against one entity, without saving it.
     *
     * <p>The entry point for a pipeline: a consumer holding an id, a vector, and the content key the model
     * actually embedded. It reads the entity to check that key still applies and then writes one vector --
     * no merge, no summary recomputation, no walk of the reachable graph.
     *
     * <pre>{@code
     * images.supplyVector(event.assetId(), "pixels",
     *         new EmbeddingVector(event.values(), MODEL_ID, event.dims(), event.computedAt()),
     *         event.contentHash());
     * }</pre>
     *
     * <p><b>{@code computedFor} is what makes this safe under at-least-once delivery.</b> If the entity has
     * moved on to different content since the model ran, the vector is discarded and this returns
     * {@code false} -- a normal outcome of a slow producer racing an edit, not a failure. Redelivery of the
     * same event simply stores the same vector again.
     *
     * @param computedFor the {@code @ExternalVector} {@code keyField} value the producer actually embedded
     * @return whether the vector was stored
     * @throws IllegalArgumentException if no such entity exists, if the type declares no
     *                                  {@code @ExternalVector} of that name, or if the vector's own
     *                                  {@code modelId()} disagrees with the declared model
     */
    boolean supplyVector(UUID id, String vectorName, EmbeddingVector vector, String computedFor);

    /**
     * Entities whose {@code vectorName} has not been supplied for the content they currently reference --
     * the backlog a producer works through.
     *
     * <p>Covers both causes at once, because they mean the same thing to a producer: never supplied, and
     * supplied for content since replaced. Intended for backfills and for re-driving after a dead-letter
     * drain, both of which sweep the whole type; a per-item "is this one done yet" belongs in whatever
     * status the application already keeps, not here.
     */
    List<T> findPendingVector(String vectorName, int limit);

    // ---- vector search as a builder (OMI-230) ------------------------------------------------------
    //
    // The counterpart to the findNearestBy<Field>Vector… method-name convention, for the searches a method
    // name cannot carry: a predicate composed at runtime, a page offset, or a one-off shape not worth
    // declaring a method for. Both idioms compile to the same query -- see NearestQuery.

    /**
     * Starts a search against each entity's own combined {@code vector()}.
     *
     * @see NearestQuery
     */
    NearestQuery<T> nearest();

    /**
     * Starts a search against one {@code @Vectorize} field's own vector.
     *
     * @param vectorizeField the field's name as declared (e.g. {@code "caption"}), not the woven accessor's
     * @throws IllegalArgumentException if the entity has no such {@code @Vectorize} field -- naming the ones
     *                                  it does have, the same way an invalid {@code findNearestBy…Vector}
     *                                  method is rejected at repository-creation time
     */
    NearestQuery<T> nearestBy(String vectorizeField);

    /**
     * Starts a search against the summary vector -- the decay-weighted arithmetic over the entity and its
     * {@code @Summary} descendants.
     *
     * <p><b>In whichever model the reference vector came from</b>, which is every model, not only the
     * configured one: the backend resolves which storage answers from {@code reference.modelId()}, so a
     * container's summary in a model that only ever arrives through {@code @ExternalVector} is searchable
     * here with nothing extra (OMI-458). Whether that is an indexed lookup or an in-memory fold depends on
     * {@code @Summary(persistModelSummaries = true)}; the answer is the same either way.
     *
     * <p>A container carrying both a {@code @Vectorize} field and an {@code @ExternalVector} has <em>two</em>
     * coherent summaries, and which one this searches is decided by the vector you pass. When that is worth
     * saying out loud -- and worth having checked -- add {@link NearestQuery#inModel(String)}.
     */
    NearestQuery<T> nearestBySummary();

    /**
     * Starts a search against the concatenated text vector -- a real embedding of assembled subtree text,
     * as against {@link #nearestBySummary()}'s arithmetic over already-computed vectors.
     *
     * @throws IllegalArgumentException if the entity type does not participate in concatenated text
     *                                  vectoring, since nothing would ever be stored for this to search
     */
    NearestQuery<T> nearestByConcatenatedText();
}
