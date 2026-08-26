package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.VectorMath;

import java.util.ArrayList;
import java.util.Comparator;
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
     * Saves several entities, embedding all their vectors up front in as few provider calls as possible
     * (OMI-266) -- see {@link JavAIRepository#saveAll} for the consumer-facing contract.
     *
     * <p>The default is the whole feature for a backend with no transaction of its own: warm the union of
     * every entity's subgraph, then save each by the ordinary path, which finds every vector already
     * computed. The warm happens before the first write deliberately, so the provider round trips are not
     * held inside whatever unit of work the saves open.
     *
     * <p>Only the Postgres backend overrides this, and only to add atomicity -- it wraps the same loop in one
     * transaction. The batching itself is identical on all three.
     */
    default List<Object> saveAll(Class<?> entityType, List<Object> entities, SummaryPolicy summaryPolicy) {
        JavAIRuntime.warmSubgraphsForPersistence(entities);
        List<Object> saved = new ArrayList<>(entities.size());
        for (Object entity : entities) {
            saved.add(save(entityType, entity, summaryPolicy));
        }
        return saved;
    }

    /**
     * Re-embeds and re-persists every entity of {@code entityType}, in batches -- the shared body behind
     * {@link #reindex} and every backend's own {@link #reindexAll}.
     *
     * <p>Chunked rather than warmed in one pass: a re-index is the one operation guaranteed to touch every
     * row in the store, so gathering the whole table's texts before issuing any request would trade a latency
     * problem for a memory one. Each chunk is warmed and then saved.
     *
     * <p>Note it deliberately does <em>not</em> route through {@link #saveAll}: that would put a whole chunk
     * in one transaction, and a maintenance pass over an entire table has no business widening the
     * transaction boundary the per-entity saves already have.
     */
    default void reindexInChunks(Class<?> entityType, SummaryPolicy summaryPolicy) {
        List<Object> all = findAll(entityType);
        for (int start = 0; start < all.size(); start += REINDEX_CHUNK_SIZE) {
            List<Object> chunk = all.subList(start, Math.min(start + REINDEX_CHUNK_SIZE, all.size()));
            JavAIRuntime.warmSubgraphsForPersistence(chunk);
            for (Object entity : chunk) {
                save(entityType, entity, summaryPolicy);
            }
        }
    }

    /** How many entities a re-index warms at a time. Matches {@code JavAIRuntime.precomputeVectors}' own
     *  default chunk size, which is what ultimately bounds the request; this bounds how much of the table is
     *  held in flight around it. */
    int REINDEX_CHUNK_SIZE = 100;

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
     * currently configured -- so a backend overrides this only to change <em>when</em> the {@code @Summary}
     * recomputation happens, never to change what is re-embedded. Postgres is the one that does, running the
     * whole pass {@code QUEUE_ONLY} and draining once at the end (OMI-255). (This previously claimed no
     * backend needed to override it, which had not been true since that ticket.)
     */
    default void reindex(Class<?> entityType) {
        reindexInChunks(entityType, SummaryPolicy.RECOMPUTE_AFTER_COMMIT);
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

    /**
     * Stores an externally-computed vector against one entity (OMI-290).
     *
     * <p>Backend-agnostic by construction, and deliberately so: the decision this has to make -- does the
     * entity still reference the content this vector was computed for? -- lives in Vector Core, and the
     * write it then performs is one the backend already knows how to do. So the default is the whole
     * implementation on every backend, and a backend overrides it only if it can do the write more
     * narrowly.
     *
     * <p><b>It loads the entity, and cannot not.</b> The content-key comparison is against the entity's own
     * field, so there is nothing to compare without reading it. What it avoids is the rest of a
     * {@code save()}: no merge, no summary recomputation, no walk of the reachable graph.
     *
     * @return {@code true} if stored; {@code false} if the vector described content the entity has since
     *         moved on from -- the ordinary outcome of a slow producer racing an edit, not an error
     * @throws IllegalArgumentException if no such entity exists, or if the vector's model disagrees with
     *                                  what the type declares
     */
    default boolean supplyVector(Class<?> entityType, UUID id, String vectorName, EmbeddingVector vector,
            String computedFor) {
        Object entity = findById(entityType, id).orElseThrow(() -> new IllegalArgumentException(
                "No " + entityType.getName() + " with id " + id + " -- cannot store an external vector"
                        + " against an entity that does not exist"));
        if (!JavAIRuntime.supplyVector(entity, vectorName, vector, computedFor)) {
            return false;
        }
        writeExternalVector(entityType, entity, vectorName);
        return true;
    }

    /** Persists one already-supplied {@code @ExternalVector} on {@code entity}, without touching anything
     *  else about it -- the narrow write behind {@link #supplyVector}. */
    void writeExternalVector(Class<?> entityType, Object entity, String vectorName);

    /**
     * Refuses a concatenated-text search whose reference is from a model that vector cannot exist in
     * (OMI-458).
     *
     * <p>{@code concatenatedTextVector()} is <b>one real embedding of one assembled string</b>, produced by
     * the configured provider. So it exists in that provider's model and in no other -- not "rarely", but by
     * construction, for every entity that ever participates. A reference from anywhere else is therefore
     * asking for something no row, node or document can hold.
     *
     * <p>⚠️ <b>Refused rather than answered empty, because empty is a lie here.</b> An empty result is what a
     * caller also gets from a corpus that genuinely has no near matches, and nothing distinguishes the two --
     * so the query looks answered. Worse, it looks answered <em>consistently</em>: every repetition returns
     * the same nothing. This is the one place a wrong model produces a plausible non-answer instead of a
     * missing table, which is why it is the one place that has to say so out loud.
     *
     * <p>Unlike a summary search, there is no fold to fall back to: folding needs a value that exists to be
     * folded, and this one does not exist in that model for anything.
     *
     * <p>Silent when the provider cannot name its own model -- there is nothing to compare against, and a
     * refusal derived from an unknown is worse than the search it would block.
     */
    default void requireConcatenatedTextInConfiguredModel(NearestSpec spec) {
        if (spec.kind() != DerivedQueryMethods.Kind.CONCATENATED_TEXT) {
            return;
        }
        String configured = JavAIRuntime.currentModelId();
        String referenceModel = spec.reference().modelId();
        if (configured == null || configured.equals(referenceModel)) {
            return;
        }
        throw new IllegalArgumentException("A concatenated-text search needs a reference from '" + configured
                + "', but this one is from '" + referenceModel + "'. The concatenated text vector is a single"
                + " embedding of assembled text, produced by the configured provider -- so it exists in that"
                + " model and in no other, for every entity, and nothing could match this reference. Answering"
                + " an empty list would be indistinguishable from a corpus with no near matches. Either embed"
                + " your query with the configured provider, or -- if you wanted the model-scoped aggregate"
                + " over a subtree's vectors rather than an embedding of its text -- use nearestBySummary(),"
                + " which does serve '" + referenceModel + "'.");
    }

    // ---- model-scoped summary search without an index (OMI-458) ------------------------------------

    /**
     * Whether a summary search must be answered by folding candidates in memory rather than by ranking a
     * stored vector -- <b>one rule, shared by every backend</b>, so the three cannot disagree about when a
     * query has an index.
     *
     * <p>True in exactly one situation: the search names a model that this entity's {@code @Summary} subtree
     * declares through an {@code @ExternalVector}, and the type has not opted into persisting that model's
     * summaries with {@code @Summary(persistModelSummaries = true)}. The value is real and computable and
     * nothing stored it.
     *
     * <p>⚠️ <b>Decided from the declaration, never from whether a table or property happens to hold
     * anything.</b> An un-backfilled corpus and a corpus with no such model are indistinguishable in
     * storage, so a "is anything there?" test would fold for a deployment merely mid-backfill and then stop
     * folding partway through it -- the cost and the answer both changing under a caller while nothing about
     * their code did.
     *
     * <p>⚠️ <b>The ambient model is never folded.</b> Its row is written by the ordinary entity-grain path
     * and always has been, so an {@code @ExternalVector} that happens to declare the configured provider's
     * own model must not drag a working indexed search onto this path.
     */
    default boolean foldsSummaryInMemory(Containment containment, Class<?> entityType, NearestSpec spec) {
        if (spec.kind() != DerivedQueryMethods.Kind.SUMMARY) {
            return false;
        }
        String modelId = spec.resolvedModelId();
        if (modelId == null || modelId.equals(JavAIRuntime.currentModelId())) {
            return false;
        }
        return containment.declaredSubtreeModels(entityType).contains(modelId)
                && !containment.perModelSummaryModels(entityType).contains(modelId);
    }

    /**
     * Ranks by {@code summaryVector(modelId)} computed on the spot -- the honest answer to a summary search
     * with no index, for a backend that has no narrowing to apply on top of it.
     *
     * <p>Backend-agnostic for the same reason {@link #supplyVector} is: the decision lives in Vector Core
     * and the only store operation involved is {@link #findAll}, which every backend implements and which
     * serves each entity its stored vectors as it loads. The Postgres backend overrides this with a version
     * that also honours a relational predicate; Neo4j and MongoDB refuse a narrowed vector search outright,
     * so for them this is the whole of it.
     *
     * <p>A candidate whose summary is absent in this model is skipped rather than ranked last -- a
     * content-free vector has no direction and must never occupy a slot in someone's top N, which is the
     * same rule the indexed paths apply by ignoring null vectors.
     *
     * <p>⚠️ <b>Cost is proportional to the corpus</b>, not to the result. That is the argument for the flag,
     * not a reason to treat this as equivalent to having one.
     */
    default List<Ranked<Object>> foldNearestBySummary(Class<?> entityType, NearestSpec spec) {
        String modelId = spec.resolvedModelId();
        List<Ranked<Object>> scored = new ArrayList<>();
        for (Object candidate : findAll(entityType)) {
            if (!(candidate instanceof JavAIVectorizable vectorizable)) {
                continue;
            }
            EmbeddingVector summary = vectorizable.summaryVector(modelId);
            if (summary.isAbsent()) {
                continue;
            }
            scored.add(new Ranked<>(candidate, VectorMath.cosineSimilarity(spec.reference(), summary)));
        }
        scored.sort(Comparator.comparingDouble(Ranked<Object>::similarity).reversed());
        int from = Math.min(spec.offset(), scored.size());
        int to = Math.min(Math.addExact(from, spec.limit()), scored.size());
        return List.copyOf(scored.subList(from, to));
    }

    /**
     * Every entity of {@code entityType} whose {@code vectorName} has not been supplied for the content it
     * currently references -- the backlog a producer works through (OMI-290).
     *
     * <p>Necessary because the vector lives outside the entity's own table/label/collection, so a caller
     * cannot express this as an ordinary derived finder without reaching into storage JavAI owns. Per-item
     * "is this one done yet" is a different question, and belongs in whatever status the application already
     * keeps; this is for backfills and for re-driving after a dead-letter drain.
     *
     * <p><b>A scan, and honestly so.</b> It reads the type and keeps those whose vector reads absent, which
     * is exactly the question being asked and is correct on every backend without a line of store-specific
     * code. It is also O(rows), which is the right shape for the two jobs it exists for -- both of which
     * sweep the whole type anyway -- and the wrong shape for polling it per upload. A backend can override
     * with an anti-join against its own vector storage if that ever stops being true.
     *
     * <p>Note "absent" already covers both causes: never supplied, and supplied for content since replaced.
     * They need no separate handling because a save writes the new content key and deletes the superseded
     * row in the same flush, so the two states are indistinguishable at rest -- as they should be, since
     * both mean the same thing to a producer.
     */
    default List<Object> findPendingVector(Class<?> entityType, String vectorName, int limit) {
        List<Object> pending = new ArrayList<>();
        for (Object entity : findAll(entityType)) {
            if (entity instanceof dev.xtrafe.javai.model.JavAIVectorizable vectorizable
                    && vectorizable.externalVector(vectorName).isAbsent()) {
                pending.add(entity);
                if (pending.size() >= limit) {
                    break;
                }
            }
        }
        return pending;
    }

    Optional<Object> findById(Class<?> entityType, UUID id);

    List<Object> findAll(Class<?> entityType);

    /**
     * How many entities of {@code entityType} the store holds (OMI-460).
     *
     * <p>Abstract rather than a {@code findAll(entityType).size()} default on purpose: that default is
     * exactly the waste {@code JavAIRepository.count()} exists to remove, and inheriting it silently would
     * leave a backend looking like it had implemented the method. Every store this project targets counts
     * natively.
     */
    long count(Class<?> entityType);

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

    // ---- declared queries: @Query / @Modifying (OMI-398) -------------------------------------------
    // Three primitives, mirroring the four above: DeclaredQuery owns the annotation, the parameter binding,
    // the return-type adaptation and Pageable/Sort/Limit; a backend runs a query and hands back rows, a
    // count, or an affected-row count.
    //
    // The defaults REFUSE rather than accept, unlike validateDerivedQuery's. That follows inTransaction's
    // precedent, and for the same reason: this is a capability one backend has and the others structurally
    // do not, so a new backend must answer it deliberately instead of inheriting a silent no-op. A JPQL or
    // SQL string means nothing to Neo4j or MongoDB, and translating one would be a query engine, not a shim.

    /**
     * Rejects, at repository-creation time, a declared query this backend cannot serve -- and on the two
     * backends that serve none, rejects every one of them.
     *
     * <p>{@link DeclaredQuery#parse} has already validated everything reflection can see. What is left is
     * store-specific: on Postgres, whether the query text parses at all and whether a write touches state
     * JavAI maintains out of band.
     */
    default void validateDeclaredQuery(Class<?> entityType, DeclaredQuery query) {
        throw new UnsupportedOperationException(declaredQueriesUnsupported(query));
    }

    /** Runs a declared select under {@code constraints}, hydrating entity results exactly as {@link #findAll}
     *  hydrates them. */
    default List<Object> runDeclaredQuery(Class<?> entityType, DeclaredQuery query, Object[] args,
            DerivedFinderQuery.Constraints constraints) {
        throw new UnsupportedOperationException(declaredQueriesUnsupported(query));
    }

    /** Runs the {@code countQuery} of a {@code Page}-returning declared query. */
    default long runDeclaredCount(Class<?> entityType, DeclaredQuery query, Object[] args) {
        throw new UnsupportedOperationException(declaredQueriesUnsupported(query));
    }

    /** Runs a {@code @Modifying} declared query, returning how many rows it affected. */
    default long runDeclaredUpdate(Class<?> entityType, DeclaredQuery query, Object[] args) {
        throw new UnsupportedOperationException(declaredQueriesUnsupported(query));
    }

    private String declaredQueriesUnsupported(DeclaredQuery query) {
        return "@Query is supported on the Postgres backend only -- " + getClass().getSimpleName()
                + " has no query language JPQL or SQL could be translated into, and " + query.method()
                + " declares one. Express it as a derived finder (findBy…/findNearestBy…Vector), as a runtime "
                + "predicate through nearestBy(...), or use the store's own driver for this one query.";
    }

    /**
     * This backend's answer to "which containers hold this member, and which members does this container
     * hold", for {@code @Taggregate} (OMI-304) -- what {@code javai-tagging} consumes through
     * {@link JavAIPI#taggregateContainment(JavAIPersistenceConfig)} in place of the membership snapshot it
     * used to keep. Resolved lazily, never in a constructor: tagging deliberately does not depend on the
     * entity mapper being ready at construction time, and by first use registration is necessarily complete.
     */
    TaggregateContainment taggregateContainment();
}
