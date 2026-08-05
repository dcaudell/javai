package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The backend-agnostic contract {@link RepositoryInvocationHandler} dispatches to --
 * {@code RepositoryBackendHibernatePostgres} and {@code RepositoryBackendNeo4j} are its two
 * implementations, selected once via {@link JavAIPersistenceConfig.Backend}. Every method operates in
 * terms of the entity's already-woven {@code JavAIVectorizable} vectors (already computed and cached
 * in-memory by the time {@code save} is called) -- this interface only ever moves them into/out of a real
 * store, never recomputes them itself.
 */
interface RepositoryBackend {

    /** Reserved field-name sentinel for the object's own combined {@code vector()} (not a per-field one).
     *  '$'-prefixed reserved names have precedent: {@code JavAIRuntime.STATE_FIELD = "$javai$state"}. */
    String COMBINED_VECTOR_FIELD = "$vector";

    /**
     * Registers {@code entityType} as one this backend must be able to persist. Called for every
     * repository interface {@link JavAIPI#repository(Class, JavAIPersistenceConfig)} creates, before any method is actually
     * invoked -- see {@code RepositoryBackendHibernatePostgres}'s javadoc for why registration and first
     * use are different moments (Hibernate's {@code SessionFactory} metadata is immutable once built).
     */
    void registerEntityType(Class<?> entityType);

    /** What a plain {@code JavAIRepository.save(entity)} reaches, unchanged since before OMI-255. Kept
     *  abstract, rather than defaulted onto the overload below, so the two can never be left defaulting to
     *  each other -- a backend implementing neither would recurse instead of failing to compile. */
    Object save(Class<?> entityType, Object entity);

    /**
     * Saves {@code entity}, choosing when the {@code @Summary} containers above it are recomputed.
     *
     * <p>Only the Postgres backend defers summary recomputation at all, so only it can honour the argument.
     * The other two recompute inline and ignore it -- a {@code QUEUE_ONLY} save there is simply already as
     * up to date as {@code RECOMPUTE_AFTER_COMMIT} would have made it, which is a stronger guarantee than
     * asked for rather than a silent failure to deliver a weaker one.
     */
    default Object save(Class<?> entityType, Object entity, SummaryPolicy summaryPolicy) {
        return save(entityType, entity);
    }

    /**
     * Re-embeds and re-persists <em>every registered entity type</em> under the currently-configured model.
     * A datastore is re-indexed as a whole: an {@code Article}'s {@code Comment}s must be re-embedded too, or
     * the store is left straddling two models. Takes no argument precisely because no single type scopes it.
     *
     * <p>Intentionally abstract: "everything I know about" is backend-specific (which types are registered,
     * and how), so a new backend must answer it deliberately rather than inherit a wrong default.
     */
    void reindexAll();

    /**
     * Re-embeds and re-persists just {@code entityType}. The narrow counterpart to {@link #reindexAll()},
     * for when a caller knowingly wants one type re-embedded and accepts that other types stay on whatever
     * model they were last written under.
     *
     * <p>Backend-agnostic by construction -- {@code save()} always writes under whichever provider is
     * currently configured -- so no backend needs to override this.
     */
    default void reindex(Class<?> entityType) {
        for (Object entity : findAll(entityType)) {
            save(entityType, entity);
        }
    }

    /**
     * Runs {@code body} so that every repository call it makes against this backend commits, or rolls back,
     * as one unit of work -- {@link JavAIPI#inTransaction}'s SPI half (OMI-146).
     *
     * <p>Refuses by default rather than silently running the body without any transaction at all: a caller
     * reaching for this method is asking for atomicity across several calls, and quietly giving them the
     * per-call behavior they were trying to escape would be worse than telling them the backend can't. Only
     * the Postgres/Hibernate backend overrides it; Neo4j's driver-level transactions and MongoDB's
     * multi-document ones are real but are not wired through this SPI in this phase.
     */
    default <T> T inTransaction(Supplier<T> body) {
        throw new UnsupportedOperationException("JavAIPI.inTransaction(...) is supported on the Postgres "
                + "backend only in this phase -- " + getClass().getSimpleName() + " runs each repository call "
                + "as its own unit of work. Compose the calls so each is independently safe to retry, or use "
                + "the store's own driver/template transaction API directly for this sequence.");
    }

    Optional<Object> findById(Class<?> entityType, UUID id);

    List<Object> findAll(Class<?> entityType);

    void deleteById(Class<?> entityType, UUID id);

    // ---- vector search (OMI-230 collapsed the three findNearestBy* methods into one) -----------------

    /**
     * Ranks {@code entityType} by similarity to {@code spec}'s reference vector, hydrating each hit exactly
     * as {@link #findById} would, in nearest-first order.
     *
     * <p><b>One method rather than the three this used to be</b> ({@code findNearestByFieldVector}/
     * {@code …BySummaryVector}/{@code …ByConcatenatedTextVector}). Those differed only in which stored vector
     * to rank against, which {@link NearestSpec#kind()} now carries, and adding a predicate, an offset and a
     * distance to each of three signatures across three backends would have multiplied a difference that was
     * never really there. The kinds still differ in <em>where</em> the vector lives -- notably on Postgres,
     * where the field-grain and entity-grain tables are separate -- which is a backend's business, not the
     * SPI's.
     *
     * <p>Hits carry their similarity because the caller may have asked for it ({@link Ranked}); a caller who
     * did not simply gets the entities unwrapped a layer up, so a backend never has to know which idiom or
     * return type asked. Similarity is normalized to cosine in {@code [-1, 1]} <em>by the backend</em>, since
     * only the backend knows what its own store's score meant -- see {@link Ranked} for why that conversion
     * cannot be left to a shared helper.
     *
     * @see NearestSpec for the limit-applies-after-the-predicate contract
     */
    List<Ranked<Object>> findNearest(Class<?> entityType, NearestSpec spec);

    /**
     * Rejects, at repository-creation time, a vector search this specific backend structurally cannot serve.
     *
     * <p>The case this exists for is narrowing. A store whose vector index answers only "the top K nearest"
     * cannot apply a relational predicate <em>before</em> that K is chosen, and the two ways to paper over it
     * are both worse than refusing: over-fetching returns fewer than the caller asked for whenever the
     * predicate is selective, and post-filtering silently answers a different question. Refusing loudly, at
     * creation time, is the same bar {@link #validateDerivedQuery} already sets for relational finders and
     * the same one {@code @Any} and {@code KnowledgeGraph} fields are held to per backend.
     *
     * <p>The default accepts everything, since a backend that can narrow has nothing to add.
     */
    default void validateNearestQuery(Class<?> entityType, NearestSpec spec) {
    }

    // ---- ordinary Spring-Data-style derived finders (OMI-138) --------------------------------------
    // These four primitives + validation are all a backend implements; DerivedFinderQuery owns the method
    // name grammar, return-type adaptation, and Pageable/Sort/Limit handling. A backend only translates the
    // BoundPart predicate tree into its native query language and applies the resolved Constraints.

    /** Rejects, at repository-creation time, any derived finder this specific backend structurally cannot
     *  serve -- e.g. a nested property path reaching through a relationship the backend doesn't map for
     *  filtering. The default accepts everything {@link DerivedFinderQuery#parse} already validated;
     *  backends override to add their own store-specific feasibility checks. Must throw
     *  {@code IllegalArgumentException} with a clear, field-naming message, never fail later on first call. */
    default void validateDerivedQuery(Class<?> entityType, DerivedFinderQuery query) {
    }

    /** Runs the derived finder's predicate under the given {@code constraints} (ordering + windowing) and
     *  returns the matching entities, already hydrated the same way {@link #findAll} hydrates. */
    List<Object> findByDerivedQuery(
            Class<?> entityType, DerivedFinderQuery query, Object[] args, DerivedFinderQuery.Constraints constraints);

    /** Counts entities matching the derived finder's predicate (ordering/windowing intentionally ignored). */
    long countByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args);

    /** Whether any entity matches the derived finder's predicate. */
    boolean existsByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args);

    /** Deletes every entity matching the derived finder's predicate, returning how many were removed. */
    long deleteByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args);
}
