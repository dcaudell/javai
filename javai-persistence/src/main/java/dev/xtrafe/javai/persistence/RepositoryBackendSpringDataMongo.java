package dev.xtrafe.javai.persistence;

import com.mongodb.MongoCommandException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.repository.query.parser.Part;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * MongoDB backend built on Spring Data MongoDB: {@link MongoTemplate} is used purely as the configured
 * connection/database holder (the same role {@code SessionFactory}/{@code Driver} play for the other two
 * backends), while the actual vector read/write/search work is issued directly against the raw driver
 * ({@link MongoCollection}/{@link Document}) rather than through Spring Data's own POJO-mapping or
 * {@code @VectorSearch} annotation surface -- mirroring this module's established pattern of a reflective,
 * hand-rolled read/write path (raw JDBC under Hibernate for Postgres, raw Cypher under the Neo4j driver for
 * Neo4j) rather than depending on a higher-level framework feature for the part that's actually novel here.
 *
 * <p><b>Mapping rules</b>, closely mirroring {@code RepositoryBackendNeo4j}'s own (see that class's javadoc
 * for the full rationale, repeated only in summary here): the entity's simple class name is its collection
 * name (same Phase 0 assumption -- two distinct types sharing a simple name isn't supported); every
 * {@code @Vectorize} field becomes a {@code <field>Vector__<model>} document field (plus a
 * {@code ...ComputedAt__<model>} sibling); the combined {@code vector()}/{@code summaryVector()} become
 * {@code vector__<model>}/{@code summaryVector__<model>}; every field whose *declared type* is a
 * {@code Map}, {@code Collection}, or {@code JavAIVectorizable} is a <b>reference</b> field -- stored as
 * just an {@code {type, id}} pointer (plus {@code ordinal}/{@code key} for collection/map members), never
 * embedded -- so a related type (e.g. {@code Comment}) keeps its own top-level collection and stays
 * independently vector-searchable via its own repository, the same "every entity type is independently
 * queryable" symmetry both other backends share. Every other simple-typed field becomes a plain document
 * field. Anything else is silently skipped, the same documented Phase 0 boundary as Neo4j.
 *
 * <p><b>Writes are additive ({@code $set}), never a whole-document replace.</b> A naive {@code replaceOne}
 * would destroy any older embedding model's already-written vector fields on every subsequent save --
 * exactly the failure mode {@code RepositoryBackendNeo4j}'s {@code SET n += $props} avoids for the same
 * reason. Every write here goes through {@code updateOne} with one {@link Updates#set} per field, upserting
 * the document -- so an older model's {@code <field>Vector__<oldModel>} field is simply never touched once
 * a newer model starts writing its own differently-named field, identical to Neo4j's node-property
 * qualification story. Reverting the configured provider needs no migration: {@code findNearestBy*}
 * resolves the field name and search index from the reference vector's own {@code modelId()}.
 *
 * <p><b>One MongoDB Search vector index per (collection, field, model)</b>, created lazily via the raw
 * {@code createSearchIndexes} database command (not a driver-version-specific typed builder, for the same
 * "hand-rolled command, not a framework abstraction" reason vector read/write uses raw {@link Document}s) --
 * cached in {@link #vectorIndexesEnsured} after the first successful creation per process, mirroring
 * {@code RepositoryBackendNeo4j.ensureVectorIndex}'s identical cache. Unlike Postgres's HNSW index or
 * Neo4j's native vector index, a MongoDB Search index builds <em>asynchronously</em> in the background --
 * {@link #awaitIndexQueryable} polls until it reports {@code queryable: true} before the first query against
 * it, a real behavioral difference from the other two backends worth calling out explicitly rather than
 * leaving as a latent, hard-to-diagnose flake on a fresh index.
 *
 * <p><b>{@code Map} fields must be keyed by {@code String}</b>, validated eagerly at registration time --
 * the same limitation both other backends share, for the same round-trip reason (see
 * {@link #validateMapKeyTypesAreSupported}), even though MongoDB documents could technically hold richer
 * keys; kept for cross-backend consistency.
 *
 * <p><b>Related entity types are auto-registered recursively</b> ({@link #registerEntityType}), matching
 * {@code RepositoryBackendHibernatePostgres}'s friendlier behavior rather than {@code RepositoryBackendNeo4j}'s
 * "register each type explicitly" limitation -- nothing about MongoDB's driver imposes Hibernate's
 * boot-time-immutable-metadata constraint that originally forced Neo4j's simpler approach, so there's no
 * reason to inherit it here. Reference documents also carry the target's fully-qualified class name
 * directly (see {@link #referenceDocument}), so hydration never actually depends on this recursive
 * registration having happened first -- it's purely the friendlier, Postgres-like convenience, not a
 * correctness requirement the way Neo4j's label registry is.
 *
 * <p><b>{@code deleteById} does not cascade</b> to referenced entities' own documents -- unlike Postgres
 * (which cascades collection members) and Neo4j (whose {@code DETACH DELETE} removes relationships but not
 * the related nodes either, for what it's worth). A referenced document simply becomes unreferenced, not
 * deleted. Documented here as a known Phase 0 boundary, not an oversight.
 */
final class RepositoryBackendSpringDataMongo implements RepositoryBackend {

    /** MongoDB's {@code CallbackCanceled} -- see {@link #isTransientSearchServiceError} for why a
     *  Search-Index-Management command comes back with this while the deployment is still starting. */
    private static final int CALLBACK_CANCELED_ERROR_CODE = 90;

    private final JavAIPersistenceConfig config;
    private final Set<Class<?>> registeredEntityTypes = ConcurrentHashMap.newKeySet();

    /** See {@link #containment()} -- resolved on first use, never in the constructor. */
    private volatile Containment containment;

    /** One-time index creation per {@code @Taggregate} edge; see {@link #ensureTaggregateIndex}. */
    private final Set<String> taggregateIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final Set<String> vectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final Object bootstrapLock = new Object();
    private volatile MongoTemplate mongoTemplate;

    RepositoryBackendSpringDataMongo(JavAIPersistenceConfig config) {
        this.config = config;
        // Types the caller named explicitly, plus every @Entity under any package they asked us to scan.
        // Registered up front so the entity set is complete before anything can be built -- which is what
        // removes registration ordering as a concern for the caller (OMI-214).
        for (Class<?> scanned : EntityPackageScanner.scan(
                config.entityPackages(), Thread.currentThread().getContextClassLoader() != null
                        ? Thread.currentThread().getContextClassLoader()
                        : getClass().getClassLoader(),
                config.excludedEntityTypes(), config.excludedEntityPackages())) {
            registerEntityType(scanned);
        }
        for (Class<?> additional : config.additionalEntityTypes()) {
            registerEntityType(additional);
        }
    }

    @Override
    public void registerEntityType(Class<?> entityType) {
        registerEntityTypeRecursively(entityType, new HashSet<>());
    }

    /** Mirrors {@code RepositoryBackendHibernatePostgres.registerEntityTypeRecursively}: walks
     *  {@code entityType} and, recursively, every related type reachable through its own reference fields,
     *  so a caller only needs to realize a repository for the "root" of an object graph. {@code visited}
     *  guards against infinite recursion through a cyclic graph. */
    private void registerEntityTypeRecursively(Class<?> entityType, Set<Class<?>> visited) {
        if (!visited.add(entityType)) {
            return;
        }
        if (registeredEntityTypes.add(entityType)) {
            validateMapKeyTypesAreSupported(entityType);
        validateNoAnyFields(entityType);
            validateNoKnowledgeGraphFields(entityType);
        }
        for (Field field : EntityReflection.allFields(entityType)) {
            Class<?> relatedType = relatedEntityType(field);
            if (relatedType != null) {
                registerEntityTypeRecursively(relatedType, visited);
            }
        }
    }

    /** The related type reachable through {@code field}, if any -- {@code Map}/{@code Collection} checked
     *  before the plain {@code JavAIVectorizable} case, since a JavAI collection field (e.g.
     *  {@code JavAILinkedHashMap}) is itself {@code JavAIVectorizable}-assignable but its *related* type is
     *  its value/element type, not the collection class itself. */
    private static Class<?> relatedEntityType(Field field) {
        Class<?> fieldType = field.getType();
        if (Map.class.isAssignableFrom(fieldType)) {
            return genericTypeArgument(field, 1);
        }
        if (Collection.class.isAssignableFrom(fieldType)) {
            return genericTypeArgument(field, 0);
        }
        if (JavAIVectorizable.class.isAssignableFrom(fieldType)) {
            return fieldType;
        }
        return null;
    }

    /** Fails fast, at registration time, for a {@code Map} reference field keyed by anything other than
     *  {@code String} -- mirrors both other backends' identical limitation/validation for the same
     *  round-trip reason. */
    /**
     * Rejects {@code @Any} fields at registration, rather than silently dropping them at save time.
     *
     * <p>{@code @Any} is a Hibernate mapping: a to-one association whose target may be any of several
     * unrelated entities, resolved through a discriminator column. This backend's mapping is hand-rolled and
     * has no discriminator concept, and its reference detection keys off the declared field type -- which for
     * {@code @Any} is deliberately a plain interface. So such a field matched neither the reference path nor
     * the simple-value path and fell into the documented "anything else is silently skipped" boundary:
     * measured empirically, the save succeeded and the association came back {@code null}. Silent data loss
     * is a considerably worse outcome than an unsupported-feature error, and it is invisible until someone
     * notices the field is empty.
     *
     * <p>Mirrors {@code validateNoKnowledgeGraphFields}, which is this codebase's established treatment of a
     * feature one backend supports and another does not: fail loudly, at registration, naming the backend
     * that does support it. {@code @Any} is Postgres-only for the same kind of reason
     * {@code KnowledgeGraph} is Neo4j-only -- see doc/ai-guidance/persistence-support-matrix.md (OMI-212).
     */
    private static void validateNoAnyFields(Class<?> entityType) {
        for (Field field : EntityReflection.allFields(entityType)) {
            if (field.isAnnotationPresent(org.hibernate.annotations.Any.class)) {
                throw new IllegalArgumentException("MongoDB persistence does not support @Any fields -- "
                        + entityType.getName() + "." + field.getName() + " is annotated @Any. Polymorphic "
                        + "discriminator associations are Postgres-only in this phase; use "
                        + "JavAIPersistenceConfig.Backend.POSTGRES for any entity type that declares one.");
            }
        }
    }

    private static void validateMapKeyTypesAreSupported(Class<?> entityType) {
        for (Field field : EntityReflection.allFields(entityType)) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Class<?> keyType = genericTypeArgument(field, 0);
            if (keyType != String.class) {
                throw new IllegalArgumentException("MongoDB persistence only supports String-keyed map "
                        + "fields -- " + entityType.getName() + "." + field.getName() + " is keyed by "
                        + (keyType == null ? "an unresolvable type" : keyType.getName()));
            }
        }
    }

    private static Class<?> genericTypeArgument(Field field, int index) {
        if (field.getGenericType() instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (index < args.length && args[index] instanceof Class<?> clazz) {
                return clazz;
            }
        }
        return null;
    }

    /** Fails fast, at registration time, for a {@code KnowledgeGraph}-typed field. Without this guard,
     *  {@link #relatedEntityType}'s plain {@code JavAIVectorizable} branch would catch it too (since
     *  {@code KnowledgeGraph extends JavAIVectorizable}) and misidentify it as an ordinary referenceable
     *  entity -- which has no {@code @Id}, so it would fail confusingly deep in {@code EntityReflection.readId}
     *  the first time a document actually needed to reference it, instead of failing clearly here.
     *  {@code KnowledgeGraph} persistence is Neo4j-only in this phase -- see
     *  {@code RepositoryBackendNeo4j}'s own {@code saveKnowledgeGraphField}/{@code hydrateKnowledgeGraphField}
     *  and doc/spec/persistence-bridge.md for why (native multi-hop traversal + hybrid similarity/structure
     *  querying has no efficient equivalent to build here in this phase). */
    private static void validateNoKnowledgeGraphFields(Class<?> entityType) {
        for (Field field : EntityReflection.allFields(entityType)) {
            if (KnowledgeGraph.class.isAssignableFrom(field.getType())) {
                throw new IllegalArgumentException("MongoDB persistence does not support KnowledgeGraph fields -- "
                        + entityType.getName() + "." + field.getName() + " is a KnowledgeGraph. KnowledgeGraph "
                        + "persistence is Neo4j-only in this phase; use JavAIPersistenceConfig.Backend.NEO4J for "
                        + "any entity type that declares one.");
            }
        }
    }

    @Override
    public Object save(Class<?> entityType, Object entity) {
        // Same rationale as both other backends' save(): locks the whole reachable subgraph and forces
        // every vector read inside saveDocument() to be accurate to the field values being written in this
        // same call, regardless of the ambient EmbeddingConsistencyMode.
        JavAIRuntime.runWithSubgraphLockedForPersistence(entity, () -> saveDocument(entity, new IdentityHashMap<>()));
        return entity;
    }

    /** Re-embeds every registered entity type, not just the repository's own -- see
     *  {@link RepositoryBackend#reindexAll} for why a datastore is re-indexed as a whole. */
    @Override
    public void reindexAll() {
        for (Class<?> registered : registeredEntityTypes) {
            reindexInChunks(registered, SummaryPolicy.RECOMPUTE_AFTER_COMMIT);
        }
    }

    @Override
    public Optional<Object> findById(Class<?> entityType, UUID id) {
        Document doc = collectionFor(entityType).find(Filters.eq("_id", id.toString())).first();
        if (doc == null) {
            return Optional.empty();
        }
        return Optional.of(hydrate(entityType, doc, new HashMap<>()));
    }

    @Override
    public List<Object> findAll(Class<?> entityType) {
        Map<UUID, Object> hydrated = new HashMap<>();
        List<Object> results = new ArrayList<>();
        for (Document doc : collectionFor(entityType).find()) {
            results.add(hydrate(entityType, doc, hydrated));
        }
        return results;
    }

    @Override
    public void deleteById(Class<?> entityType, UUID id) {
        collectionFor(entityType).deleteOne(Filters.eq("_id", id.toString()));
    }

    @Override
    public List<Ranked<Object>> findNearest(Class<?> entityType, NearestSpec spec) {
        return findNearest(entityType, vectorPropertyName(spec), spec);
    }

    /** Every kind is one document field here -- the grain problem that made Postgres' field table the wrong
     *  home for the entity-grain vectors does not arise on a store whose vectors are document fields. */
    private static String vectorPropertyName(NearestSpec spec) {
        return switch (spec.kind()) {
            case SUMMARY -> "summaryVector";
            case CONCATENATED_TEXT -> "concatenatedTextVector";
            case COMBINED -> "vector";
            case FIELD -> spec.fieldName() + "Vector";
        };
    }

    /**
     * {@code $vectorSearch}, narrowed by {@code filter} when the query asks for it (OMI-230).
     *
     * <p><b>{@code filter} is a genuine pre-filter</b>, which is what makes MongoDB able to serve this at all
     * where Neo4j cannot: Atlas applies it during the search rather than to the search's output, so
     * {@code limit} counts only documents that already satisfy the predicate. "The nearest N that also match"
     * therefore returns N of them, not "however many of the nearest N happened to match."
     *
     * <p>The predicate is resolved to an id set first and passed as {@code _id ∈ …}, rather than translated
     * into {@code filter} directly. Not a detour -- the only workable route: {@code $vectorSearch}'s filter
     * may only touch paths declared as {@code filter} fields in the index definition, and which paths a
     * predicate will touch is not knowable when the index is created. Routing through {@code _id}, which
     * {@link #ensureVectorIndex} always declares, means any predicate the ordinary derived finders can
     * express is usable here, translated by {@link #buildFilter} -- the same code, so identical semantics --
     * rather than a subset chosen by whatever the index happened to declare.
     */
    private List<Ranked<Object>> findNearest(Class<?> entityType, String basePropertyName, NearestSpec spec) {
        EmbeddingVector reference = spec.reference();
        String collectionName = collectionName(entityType);
        String qualifiedField = qualify(basePropertyName, reference.modelId());
        String indexName = vectorIndexName(collectionName, qualifiedField);
        ensureVectorIndex(collectionName, indexName, qualifiedField, reference.dims());

        Document search = new Document()
                .append("index", indexName)
                .append("path", qualifiedField)
                .append("queryVector", toDoubleList(reference.values()));
        int fetch = spec.limitIncludingOffset();
        if (spec.isNarrowed()) {
            Set<UUID> allowed = queryIds(entityType, buildFilter(entityType, spec.predicate()));
            if (allowed.isEmpty()) {
                return List.of(); // nothing satisfies the predicate, so nothing can be near and satisfy it
            }
            search.append("filter", new Document("_id",
                    new Document("$in", allowed.stream().map(UUID::toString).toList())));
        }
        search.append("numCandidates", Math.max(fetch * 10, 100)).append("limit", fetch);

        List<Bson> pipeline = List.of(
                new Document("$vectorSearch", search),
                new Document("$addFields", new Document(SCORE_FIELD, new Document("$meta", "vectorSearchScore"))));

        Map<UUID, Object> hydrated = new HashMap<>();
        List<Ranked<Object>> results = new ArrayList<>();
        int seen = 0;
        for (Document doc : collectionFor(entityType).aggregate(pipeline)) {
            if (seen++ < spec.offset()) {
                continue; // ranking is total, so skipping its head is exact
            }
            // Atlas reports a cosine index's score rescaled into (0, 1] as (1 + cosine) / 2; Ranked speaks
            // raw cosine, like the rest of JavAI, so undo the rescaling here where it is known.
            double score = doc.get(SCORE_FIELD, Number.class).doubleValue();
            results.add(new Ranked<>(hydrate(entityType, doc, hydrated), 2.0 * score - 1.0));
        }
        return results;
    }

    /** Where the pipeline parks {@code $meta: "vectorSearchScore"} so it can be read off each hit. Named
     *  with the same {@code javai_} prefix the backend's other reserved storage uses, so it cannot collide
     *  with a mapped field. */
    private static final String SCORE_FIELD = "javai_score";

    /** {@code vectorIndexesEnsured} is only updated on full success -- not just after issuing the create
     *  command -- so a failure partway through (e.g. {@link #awaitIndexQueryable} timing out) never leaves
     *  a false-positive cache entry that would make a later call skip re-checking an index that was never
     *  actually confirmed queryable. A benign race is possible if two threads race this same index before
     *  either finishes (both would redundantly issue the idempotent create + re-poll) -- acceptable, the
     *  same bar {@code RepositoryBackendNeo4j.ensureVectorIndex}'s unsynchronized {@code Set.add} check
     *  already accepts for its own index-creation race. */
    private void ensureVectorIndex(String collectionName, String indexName, String path, int dims) {
        if (vectorIndexesEnsured.contains(indexName)) {
            return;
        }
        // The _id filter field is what makes a narrowed vector search possible (OMI-230): $vectorSearch's
        // own filter may only touch paths the index declares as filter fields, and which paths a caller's
        // predicate will touch is unknowable here. Declaring _id -- always present, always unique -- lets any
        // predicate be resolved to an id set and applied as a genuine pre-filter. NOTE: an index created by
        // a JavAI older than OMI-230 lacks this path, and index definitions are not amended in place; drop
        // such an index (or the collection) to let this recreate it if narrowed search reports a bad filter.
        Document definition = new Document("fields", List.of(
                new Document("type", "vector")
                        .append("path", path)
                        .append("numDimensions", dims)
                        .append("similarity", "cosine"),
                new Document("type", "filter").append("path", "_id")));
        Document command = new Document("createSearchIndexes", collectionName)
                .append("indexes", List.of(new Document("name", indexName)
                        .append("type", "vectorSearch")
                        .append("definition", definition)));
        createSearchIndexWithRetry(command);
        awaitIndexQueryable(collectionName, indexName);
        vectorIndexesEnsured.add(indexName);
    }

    /** A freshly-started deployment's Search Index Management service ({@code mongot}) isn't reachable the
     *  instant {@code mongod} itself starts accepting ordinary connections -- confirmed empirically against
     *  a real {@code mongodb/mongodb-atlas-local} container, not assumed: {@code createSearchIndexes} can
     *  fail with "Error connecting to Search Index Management service." for the better part of a minute
     *  after the deployment's own port is already open. Retried with a bounded backoff rather than surfaced
     *  as a hard failure, since this is a transient startup condition every fresh deployment goes through
     *  once, not a real error -- any application hitting this backend right after starting its own MongoDB
     *  container would otherwise see spurious failures on its very first vector search. */
    private void createSearchIndexWithRetry(Document command) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (true) {
            try {
                mongoTemplate().getDb().runCommand(command);
                return;
            } catch (MongoCommandException e) {
                if (isDuplicateIndexError(e)) {
                    return;
                }
                if (!isTransientSearchServiceError(e) || Instant.now().isAfter(deadline)) {
                    throw e;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static boolean isDuplicateIndexError(MongoCommandException e) {
        String message = e.getErrorMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("already exist");
    }

    /**
     * Whether {@code e} means "the Search Index Management service isn't usable <em>right now</em>, but
     * should be shortly" -- as opposed to a real, terminal error worth surfacing. Two distinct causes, both
     * confirmed empirically against a real {@code mongodb/mongodb-atlas-local} container rather than assumed:
     *
     * <ul>
     *   <li><b>Not reachable yet.</b> {@code mongot} isn't listening the instant {@code mongod} starts
     *       accepting connections, and says so in the message ("Error connecting to Search Index Management
     *       service.").</li>
     *   <li><b>Went away mid-command</b> ({@code CallbackCanceled}, OMI-148). {@code mongodb-atlas-local}
     *       restarts {@code mongod} twice while coming up -- once to initialize the replica set, then again
     *       with the {@code mongotHost}/{@code searchIndexManagementHostAndPort} parameters set. A command
     *       already in flight when one of those restarts lands fails with error 90, because
     *       {@code mongod}'s shutdown cancels pending search-executor callbacks
     *       ({@code shutDownSearchTaskExecutors}). The message is
     *       "onInvoke :: caused by :: Callback was canceled" -- it names neither the service nor the cause,
     *       so it has to be matched on the code.</li>
     * </ul>
     *
     * <p>Both are transient startup conditions every fresh deployment passes through once, so both are
     * retried by the callers below rather than surfaced. A consumer that starts its own MongoDB container
     * and immediately uses this backend hits exactly this window; the alternative is spurious failures on
     * the very first vector search. Retrying stays bounded by each caller's own deadline, so a genuinely
     * dead {@code mongot} still fails, just after the wait rather than instantly.
     */
    private static boolean isTransientSearchServiceError(MongoCommandException e) {
        if (e.getErrorCode() == CALLBACK_CANCELED_ERROR_CODE) {
            return true;
        }
        String message = e.getErrorMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("search index management service");
    }

    /** MongoDB Search indexes build asynchronously in the background -- a real difference from Postgres's
     *  HNSW index and Neo4j's native vector index, both of which are usable the instant they're created.
     *  Polls {@code listSearchIndexes()} for {@code queryable: true} before returning, so the very first
     *  {@code findNearestBy*} call against a brand-new model doesn't race a still-building index.
     *  {@code listSearchIndexes()} itself is a Search Index Management operation, so it can hit the exact
     *  same "service not reachable yet" condition {@link #createSearchIndexWithRetry} guards against (see
     *  that method's own javadoc) -- treated the same way here: not yet queryable, keep polling, rather than
     *  letting a transient startup error abort the whole wait. */
    private void awaitIndexQueryable(String collectionName, String indexName) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            try {
                for (Document index : mongoTemplate().getCollection(collectionName).listSearchIndexes()) {
                    if (indexName.equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("queryable"))) {
                        return;
                    }
                }
            } catch (MongoCommandException e) {
                if (!isTransientSearchServiceError(e)) {
                    throw e;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException(
                "Vector search index '" + indexName + "' on '" + collectionName + "' did not become queryable "
                        + "within 90s");
    }

    private static String vectorIndexName(String collectionName, String qualifiedField) {
        return "javai_" + collectionName.toLowerCase(Locale.ROOT) + "_" + qualifiedField;
    }

    private static String qualify(String basePropertyName, String modelId) {
        return basePropertyName + "__" + ModelIds.sanitize(modelId);
    }

    // ---- ordinary derived finders (OMI-138): MongoDB filter translation ------------------------

    /** Rejects, at repository-creation time, a derived finder this backend can't translate. Nested filter
     *  paths are supported now (OMI-141): since related entities are stored as {@code {type, id}} reference
     *  pointers, a nested predicate is resolved by matching the referenced entities first and then matching
     *  owners whose {@code <field>.id} points at one of them -- so an intermediate segment must be a reference
     *  field of an entity. Leaf rules depend on the operator: emptiness ({@code IsEmpty}/{@code IsNotEmpty})
     *  needs a collection reference field; geo ({@code Near}/{@code Within}) needs a {@code Point} field;
     *  every other operator needs a plain document field. Sort stays limited to a root plain field. */
    @Override
    public void validateDerivedQuery(Class<?> entityType, DerivedFinderQuery query) {
        for (Part part : query.partTree().getParts()) {
            validatePartPath(entityType, part.getProperty().toDotPath(), part.getType());
        }
        for (Sort.Order order : query.partTree().getSort()) {
            validateSortPath(entityType, order.getProperty());
        }
    }

    private static void validatePartPath(Class<?> entityType, String dotPath, Part.Type type) {
        Class<?> owner = entityType;
        String[] segments = dotPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            Field field = EntityReflection.findField(owner, segments[i]);
            if (i < segments.length - 1) {
                Class<?> related = relatedEntityType(field);
                if (related == null || !isReferenceField(field)) {
                    throw new IllegalArgumentException("MongoDB derived finder cannot traverse '" + segments[i]
                            + "' on " + owner.getName() + " -- an intermediate segment must be a reference field "
                            + "(singular reference, or a Collection/Map of entities).");
                }
                owner = related;
            } else {
                validateLeaf(owner, field, type);
            }
        }
    }

    private static void validateLeaf(Class<?> owner, Field field, Part.Type type) {
        boolean collectionReference = isReferenceField(field)
                && (Map.class.isAssignableFrom(field.getType()) || Collection.class.isAssignableFrom(field.getType()));
        switch (type) {
            case IS_EMPTY, IS_NOT_EMPTY -> {
                if (!collectionReference) {
                    throw new IllegalArgumentException("MongoDB IsEmpty/IsNotEmpty needs a collection reference field "
                            + "-- '" + field.getName() + "' on " + owner.getName() + " is not one.");
                }
            }
            case NEAR, WITHIN -> {
                if (!Point.class.isAssignableFrom(field.getType())) {
                    throw new IllegalArgumentException("MongoDB Near/Within needs a Point field -- '"
                            + field.getName() + "' on " + owner.getName() + " is "
                            + field.getType().getSimpleName() + ".");
                }
            }
            case EXISTS -> {
                // $exists: valid on any field.
            }
            default -> {
                if (isReferenceField(field) || Point.class.isAssignableFrom(field.getType())) {
                    throw new IllegalArgumentException("MongoDB derived finder cannot filter on '" + field.getName()
                            + "' of " + owner.getName() + " with " + type + " -- it's a reference/geo field, not a "
                            + "plain document field.");
                }
            }
        }
    }

    private static void validateSortPath(Class<?> entityType, String dotPath) {
        if (dotPath.contains(".")) {
            throw new IllegalArgumentException("MongoDB derived finder can only sort by a root plain field, not the "
                    + "nested path '" + dotPath + "' on " + entityType.getName() + " (references aren't embedded).");
        }
        Field field = EntityReflection.findField(entityType, dotPath);
        if (isReferenceField(field) || Point.class.isAssignableFrom(field.getType())) {
            throw new IllegalArgumentException("MongoDB derived finder cannot sort by '" + dotPath + "' of "
                    + entityType.getName() + " -- it's a reference/geo field, not a plain document field.");
        }
    }

    @Override
    public List<Object> findByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args,
            DerivedFinderQuery.Constraints constraints) {
        FindIterable<Document> cursor = collectionFor(entityType).find(buildFilter(entityType, query.boundOrGroups(args)));
        Document sort = sortDocument(entityType, constraints.sort());
        if (sort != null) {
            cursor = cursor.sort(sort);
            if (needsCaseInsensitiveCollation(constraints.sort())) {
                cursor = cursor.collation(
                        Collation.builder().locale("en").collationStrength(CollationStrength.SECONDARY).build());
            }
        }
        if (constraints.skip() != null) {
            cursor = cursor.skip(constraints.skip());
        }
        if (constraints.maxResults() != null) {
            cursor = cursor.limit(constraints.maxResults());
        }
        Map<UUID, Object> hydrated = new HashMap<>();
        List<Object> results = new ArrayList<>();
        for (Document doc : cursor) {
            results.add(hydrate(entityType, doc, hydrated));
        }
        return results;
    }

    @Override
    public long countByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        return collectionFor(entityType).countDocuments(buildFilter(entityType, query.boundOrGroups(args)));
    }

    @Override
    public boolean existsByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        return collectionFor(entityType).find(buildFilter(entityType, query.boundOrGroups(args))).limit(1).first() != null;
    }

    @Override
    public long deleteByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        // deleteMany, mirroring deleteById -- removes the matched documents but not the documents they
        // reference, the same documented Mongo non-cascade boundary.
        return collectionFor(entityType).deleteMany(buildFilter(entityType, query.boundOrGroups(args))).getDeletedCount();
    }

    private Bson buildFilter(Class<?> entityType, List<List<DerivedFinderQuery.BoundPart>> orGroups) {
        List<Bson> orFilters = new ArrayList<>();
        for (List<DerivedFinderQuery.BoundPart> group : orGroups) {
            List<Bson> andFilters = new ArrayList<>();
            for (DerivedFinderQuery.BoundPart part : group) {
                andFilters.add(toFilter(entityType, part));
            }
            if (!andFilters.isEmpty()) {
                orFilters.add(andFilters.size() == 1 ? andFilters.get(0) : Filters.and(andFilters));
            }
        }
        if (orFilters.isEmpty()) {
            return Filters.empty();
        }
        return orFilters.size() == 1 ? orFilters.get(0) : Filters.or(orFilters);
    }

    private Bson toFilter(Class<?> entityType, DerivedFinderQuery.BoundPart part) {
        String dotPath = part.property().toDotPath();
        // A nested path can't be matched in one document (references aren't embedded): resolve the matching
        // root ids first, then match on _id. Root-level geo/emptiness, by contrast, are on the document itself.
        if (dotPath.contains(".")) {
            Set<UUID> ids = rootIdsMatching(entityType, part);
            List<String> idStrings = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                idStrings.add(id.toString());
            }
            return Filters.in("_id", idStrings);
        }
        Field field = EntityReflection.findField(entityType, dotPath);
        String mongoField = mongoField(entityType, dotPath);
        Part.Type type = part.type();
        if (type == Part.Type.NEAR || type == Part.Type.WITHIN) {
            return geoFilter(mongoField, DerivedFinderQuery.geoCircle(part));
        }
        if (type == Part.Type.IS_EMPTY) {
            return Filters.not(Filters.exists(mongoField + ".0"));
        }
        if (type == Part.Type.IS_NOT_EMPTY) {
            return Filters.exists(mongoField + ".0");
        }
        if (type == Part.Type.EXISTS) {
            return isReferenceField(field) && (Map.class.isAssignableFrom(field.getType())
                    || Collection.class.isAssignableFrom(field.getType()))
                    ? Filters.exists(mongoField + ".0")
                    : Filters.exists(mongoField);
        }
        return scalarFilter(mongoField, part);
    }

    private static Bson scalarFilter(String field, DerivedFinderQuery.BoundPart part) {
        List<Object> a = part.arguments();
        boolean ic = part.ignoreCase();
        String caseOption = ic ? "i" : "";
        return switch (part.type()) {
            case SIMPLE_PROPERTY -> ic && a.get(0) instanceof String s
                    ? Filters.regex(field, "^" + Pattern.quote(s) + "$", "i")
                    : Filters.eq(field, toMongoValue(a.get(0)));
            case NEGATING_SIMPLE_PROPERTY -> ic && a.get(0) instanceof String s
                    ? Filters.not(Filters.regex(field, "^" + Pattern.quote(s) + "$", "i"))
                    : Filters.ne(field, toMongoValue(a.get(0)));
            case GREATER_THAN, AFTER -> Filters.gt(field, toMongoValue(a.get(0)));
            case GREATER_THAN_EQUAL -> Filters.gte(field, toMongoValue(a.get(0)));
            case LESS_THAN, BEFORE -> Filters.lt(field, toMongoValue(a.get(0)));
            case LESS_THAN_EQUAL -> Filters.lte(field, toMongoValue(a.get(0)));
            case BETWEEN -> Filters.and(
                    Filters.gte(field, toMongoValue(a.get(0))), Filters.lte(field, toMongoValue(a.get(1))));
            case IS_NULL -> Filters.eq(field, null);
            case IS_NOT_NULL -> Filters.ne(field, null);
            case LIKE -> Filters.regex(field, likeToRegex(String.valueOf(a.get(0))), caseOption);
            case NOT_LIKE -> Filters.not(Filters.regex(field, likeToRegex(String.valueOf(a.get(0))), caseOption));
            case STARTING_WITH -> Filters.regex(field, "^" + Pattern.quote(String.valueOf(a.get(0))), caseOption);
            case ENDING_WITH -> Filters.regex(field, Pattern.quote(String.valueOf(a.get(0))) + "$", caseOption);
            case CONTAINING -> Filters.regex(field, Pattern.quote(String.valueOf(a.get(0))), caseOption);
            case NOT_CONTAINING -> Filters.not(Filters.regex(field, Pattern.quote(String.valueOf(a.get(0))), caseOption));
            case REGEX -> Filters.regex(field, String.valueOf(a.get(0)), caseOption);
            case IN -> Filters.in(field, mongoValues(a.get(0)));
            case NOT_IN -> Filters.nin(field, mongoValues(a.get(0)));
            case TRUE -> Filters.eq(field, true);
            case FALSE -> Filters.eq(field, false);
            default -> throw new IllegalArgumentException(
                    "Unsupported derived-query operator " + part.type() + " for the MongoDB backend.");
        };
    }

    /** {@code $geoWithin} + {@code $centerSphere} (radius in radians = meters / earth radius). Works on a
     *  GeoJSON point field with no geospatial index required, unlike {@code $near}. */
    private static Bson geoFilter(String field, DerivedFinderQuery.GeoCircle geo) {
        double radiusRadians = geo.radiusMeters() / 6_378_137.0;
        return Filters.geoWithinCenterSphere(field, geo.longitude(), geo.latitude(), radiusRadians);
    }

    // ---- id-set resolution for nested paths (references aren't embedded) -----------------------

    /** Resolves the {@code entityType} ids matching a nested {@code part} predicate: match the leaf's owning
     *  entities first, then walk the path back to the root, at each hop matching owners whose {@code <field>.id}
     *  reference points at one of the ids resolved so far. */
    private Set<UUID> rootIdsMatching(Class<?> entityType, DerivedFinderQuery.BoundPart part) {
        String[] segments = part.property().toDotPath().split("\\.");
        Class<?>[] ownerTypes = new Class<?>[segments.length];
        Class<?> type = entityType;
        for (int i = 0; i < segments.length; i++) {
            ownerTypes[i] = type;
            type = relatedEntityType(EntityReflection.findField(type, segments[i]));
        }
        Class<?> leafOwnerType = ownerTypes[segments.length - 1];
        Set<UUID> ids = queryIds(leafOwnerType, leafFilter(leafOwnerType, segments[segments.length - 1], part));
        for (int i = segments.length - 2; i >= 0; i--) {
            ids = ownersReferencing(ownerTypes[i], segments[i], ids);
        }
        return ids;
    }

    private Bson leafFilter(Class<?> type, String field, DerivedFinderQuery.BoundPart part) {
        String mongoField = mongoField(type, field);
        return switch (part.type()) {
            case NEAR, WITHIN -> geoFilter(mongoField, DerivedFinderQuery.geoCircle(part));
            case IS_NOT_EMPTY, EXISTS -> Filters.exists(mongoField + ".0");
            case IS_EMPTY -> Filters.not(Filters.exists(mongoField + ".0"));
            default -> scalarFilter(mongoField, part);
        };
    }

    private Set<UUID> ownersReferencing(Class<?> ownerType, String field, Set<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<String> memberIdStrings = new ArrayList<>(memberIds.size());
        for (UUID id : memberIds) {
            memberIdStrings.add(id.toString());
        }
        // A reference stores its target's id under "<field>.id" (singular) or in an array of such subdocuments
        // (collection); $in matches into arrays too, so one filter covers both cardinalities.
        return queryIds(ownerType, Filters.in(field + ".id", memberIdStrings));
    }

    private Set<UUID> queryIds(Class<?> type, Bson filter) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Document doc : collectionFor(type).find(filter).projection(new Document("_id", 1))) {
            ids.add(UUID.fromString(doc.getString("_id")));
        }
        return ids;
    }

    private static List<Object> mongoValues(Object raw) {
        List<Object> converted = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object element : collection) {
                converted.add(toMongoValue(element));
            }
        }
        return converted;
    }

    /** SQL-LIKE ({@code %}/{@code _}) to a MongoDB {@code $regex}, escaping other regex metacharacters. */
    private static String likeToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }

    private Document sortDocument(Class<?> entityType, Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return null;
        }
        Document document = new Document();
        for (Sort.Order order : sort) {
            document.append(mongoField(entityType, order.getProperty()), order.isAscending() ? 1 : -1);
        }
        return document;
    }

    private static boolean needsCaseInsensitiveCollation(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return false;
        }
        for (Sort.Order order : sort) {
            if (order.isIgnoreCase()) {
                return true;
            }
        }
        return false;
    }

    /** The document field name for a property: the {@code @Id} field maps to Mongo's {@code _id} (matching
     *  {@link #saveDocument}/{@link #findById}); every other field is stored under its own name. */
    private static String mongoField(Class<?> entityType, String fieldName) {
        return fieldName.equals(EntityReflection.idField(entityType).getName()) ? "_id" : fieldName;
    }

    // ---- save: reflective document + reference mapping -----------------------------------------

    private void saveDocument(Object entity, Map<Object, UUID> alreadySaved) {
        if (alreadySaved.containsKey(entity)) {
            return;
        }
        // Assigned here, not just in save()'s entry point: a referenced entity (e.g. Article.featuredComment)
        // reaches this same method recursively via referenceValue(), and a freshly-constructed related
        // object has never had save() called on it directly, so its own @Id is still null just as often as
        // the top-level entity's is.
        if (EntityReflection.readId(entity) == null) {
            EntityReflection.writeId(entity, UUID.randomUUID());
        }
        UUID id = EntityReflection.readId(entity);
        alreadySaved.put(entity, id);

        Class<?> entityType = entity.getClass();

        Map<String, Object> updates = new HashMap<>();

        Set<String> removals = new LinkedHashSet<>();
        for (Field field : EntityReflection.allFields(entityType)) {
            String fieldName = field.getName();
            if (isIdField(field)) {
                continue; // the "_id" MERGE key, handled separately below
            }
            if (isReferenceField(field)) {
                Object value = EntityReflection.readField(entity, fieldName);
                updates.put(fieldName, referenceValue(value, alreadySaved));
                continue;
            }
            Object value = EntityReflection.readField(entity, fieldName);
            if (value instanceof Point point) {
                // GeoJSON Point ([longitude, latitude]) so $geoWithin geo finders (Near/Within) work against it.
                updates.put(fieldName, new Document("type", "Point")
                        .append("coordinates", List.of(point.getX(), point.getY())));
            } else if (isSimpleValue(value)) {
                updates.put(fieldName, toMongoValue(value));
            }
            // else: not reference-shaped but also not a simple type -- documented Phase 0 boundary, skipped.
        }
        // Not every persisted @Entity is @JavAIVectorizable -- a @Taggable-only entity (no embedding of its
        // own; see javai-tagging's own doc/spec/tagging.md "Orthogonality" section) still gets its plain
        // fields written above, it just has no vector fields to add here.
        if (entity instanceof JavAIVectorizable vectorizable) {
            // An absent vector (EmbeddingVector.absent(), OMI-187) carries no content and no dimensions, so
            // it gets no field rather than an empty array under a synthetic "<absent>" model id -- and any
            // field a previous save wrote is $unset, so a field that loses its content cannot keep matching
            // vector searches for content it no longer has.
            String currentModelId = JavAIRuntime.currentModelId();
            for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
                EmbeddingVector vector = vectorizable.fieldVector(fieldName);
                if (vector.isAbsent()) {
                    clearVectorField(removals, fieldName + "Vector", currentModelId);
                    continue;
                }
                String qualified = qualify(fieldName + "Vector", vector.modelId());
                updates.put(qualified, toDoubleList(vector.values()));
                updates.put(qualified + "ComputedAt", vector.computedAt().toString());
            }
            EmbeddingVector combined = vectorizable.vector();
            if (combined.isAbsent()) {
                clearVectorField(removals, "vector", currentModelId);
            } else {
                String qualifiedCombined = qualify("vector", combined.modelId());
                updates.put(qualifiedCombined, toDoubleList(combined.values()));
                updates.put(qualifiedCombined + "ComputedAt", combined.computedAt().toString());
            }

            EmbeddingVector summary = vectorizable.summaryVector();
            if (summary.isAbsent()) {
                clearVectorField(removals, "summaryVector", currentModelId);
            } else {
                String qualifiedSummary = qualify("summaryVector", summary.modelId());
                updates.put(qualifiedSummary, toDoubleList(summary.values()));
                updates.put(qualifiedSummary + "ComputedAt", summary.computedAt().toString());
            }

            // Concatenated text and its vector (OMI-191). Per-document fields, exactly like summaryVector
            // above -- no grain problem here, unlike Postgres' field table. The text is stored alongside so
            // re-embedding under another model needs no walk of the object graph. $unset when concatenation
            // is switched off, so a stale text vector cannot outlive the opt-in.
            EmbeddingVector concatenated = vectorizable.concatenatedTextVector();
            if (concatenated.isAbsent()) {
                clearVectorField(removals, "concatenatedTextVector", currentModelId);
                if (currentModelId != null) {
                    removals.add(qualify("concatenatedText", currentModelId));
                }
            } else {
                String qualifiedConcat = qualify("concatenatedTextVector", concatenated.modelId());
                updates.put(qualifiedConcat, toDoubleList(concatenated.values()));
                updates.put(qualifiedConcat + "ComputedAt", concatenated.computedAt().toString());
                String text = vectorizable.concatenatedText();
                String qualifiedText = qualify("concatenatedText", concatenated.modelId());
                if (text == null) {
                    // $unset rather than $set-to-null: concatenatedText() returns null for "there is no
                    // text", and a BSON null would be a stored value claiming otherwise.
                    removals.add(qualifiedText);
                } else {
                    updates.put(qualifiedText, text);
                }
            }

            // @ExternalVector fields (OMI-290). Per-document, like summaryVector above, with two
            // differences: the qualifier is the *declared* model rather than the configured one -- these
            // have nothing to do with whichever text provider is running -- and the content key the vector
            // was computed for is stored beside it. Without that key a hydrated vector is held and never
            // served, since every read compares it against the document's current content.
            for (String vectorName : JavAIRuntime.externalVectorNames(entityType)) {
                String declaredModel = JavAIRuntime.externalVectorModel(entityType, vectorName);
                EmbeddingVector external = vectorizable.externalVector(vectorName);
                if (external.isAbsent()) {
                    // Nothing supplied yet, or superseded. $unset rather than left behind: the stored vector
                    // confidently describes content this document no longer references, and a stale entry in
                    // a $vectorSearch index goes on matching forever.
                    clearVectorField(removals, vectorName + "Vector", declaredModel);
                    removals.add(qualify(vectorName + "Vector", declaredModel) + "ComputedFor");
                    continue;
                }
                String qualifiedExternal = qualify(vectorName + "Vector", external.modelId());
                updates.put(qualifiedExternal, toDoubleList(external.values()));
                updates.put(qualifiedExternal + "ComputedAt", external.computedAt().toString());
                updates.put(qualifiedExternal + "ComputedFor",
                        JavAIRuntime.externalVectorKey(entity, vectorName));
            }
        }

        // $set-based upsert, deliberately never a whole-document replaceOne -- see this class's own javadoc
        // ("Writes are additive") for why a replace would destroy older models' already-written vectors.
        List<Bson> setOps = new ArrayList<>(updates.size() + removals.size());
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            setOps.add(Updates.set(entry.getKey(), entry.getValue()));
        }
        // $unset for vectors that no longer exist. Paired with the additive $set above rather than replacing
        // it: only the named fields go, so another model's vectors are untouched.
        for (String field : removals) {
            setOps.add(Updates.unset(field));
        }
        collectionFor(entityType).updateOne(
                Filters.eq("_id", id.toString()), Updates.combine(setOps), new UpdateOptions().upsert(true));
    }

    /** Builds the value to store for one reference field: recursively saves whatever it points to (so the
     *  target has its own up-to-date document/vectors), then returns a {@code {type, id}} pointer (plus
     *  {@code ordinal}/{@code key} for collection/map members) instead of the actual referenced object. */
    private Object referenceValue(Object value, Map<Object, UUID> alreadySaved) {
        if (value instanceof Map<?, ?> map) {
            List<Document> refs = new ArrayList<>();
            int ordinal = 0;
            for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                Object element = mapEntry.getValue();
                if (element == null) {
                    continue;
                }
                saveDocument(element, alreadySaved);
                refs.add(referenceDocument(element).append("key", String.valueOf(mapEntry.getKey())).append("ordinal", ordinal++));
            }
            return refs;
        }
        if (value instanceof Collection<?> collection) {
            List<Document> refs = new ArrayList<>();
            int ordinal = 0;
            for (Object element : collection) {
                if (element == null) {
                    continue;
                }
                saveDocument(element, alreadySaved);
                refs.add(referenceDocument(element).append("ordinal", ordinal++));
            }
            return refs;
        }
        if (value != null) {
            saveDocument(value, alreadySaved);
            return referenceDocument(value);
        }
        return null;
    }

    private static Document referenceDocument(Object target) {
        return new Document("type", target.getClass().getName()).append("id", EntityReflection.readId(target).toString());
    }

    // ---- read: reflective hydration back into a plain Java object graph ------------------------

    /** {@code hydrated} caches by id within a single call so a cyclic reference graph terminates. */
    private Object hydrate(Class<?> entityType, Document doc, Map<UUID, Object> hydrated) {
        UUID id = UUID.fromString(doc.getString("_id"));
        Object cached = hydrated.get(id);
        if (cached != null) {
            return cached;
        }
        Object entity;
        try {
            entity = entityType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(entityType + " needs a no-arg constructor to be hydrated from MongoDB", e);
        }
        EntityReflection.writeId(entity, id);
        hydrated.put(id, entity);

        for (Field field : EntityReflection.allFields(entityType)) {
            String fieldName = field.getName();
            if (isIdField(field) || isReferenceField(field) || !doc.containsKey(fieldName)) {
                continue;
            }
            setFieldFromMongoValue(entity, field, doc.get(fieldName));
        }
        for (Field field : EntityReflection.allFields(entityType)) {
            if (isReferenceField(field)) {
                hydrateReferenceField(entity, field, doc.get(field.getName()), hydrated);
            }
        }
        hydrateVectors(entityType, entity, doc);
        return entity;
    }

    /**
     * Serves each {@code @Vectorize} field's already-stored vector straight into the materialized
     * instance's cache slots, so reading it costs nothing instead of a fresh model call (OMI-187).
     *
     * <p>Free here, as on Neo4j: JavAI's vectors are ordinary document fields, so they came back with the
     * document this method is already reading -- no extra query. Only the currently-configured model's
     * fields are read (they are qualified per model so several models' vectors can coexist), and
     * {@code JavAIRuntime.hydrateFieldVector} declines any slot a setter has touched, so a real mutation
     * still wins over the stored value.
     */
    /** Marks a vector field (and its timestamp) for {@code $unset} under the current model. */
    private static void clearVectorField(Set<String> removals, String baseName, String modelId) {
        if (modelId == null) {
            return; // no model named, so no field name to target -- see JavAIEmbeddingProvider.modelId()
        }
        String qualified = qualify(baseName, modelId);
        removals.add(qualified);
        removals.add(qualified + "ComputedAt");
    }

    /**
     * Writes one {@code @ExternalVector}'s document fields and nothing else -- the narrow write behind
     * {@code supplyVector} (OMI-290). Not a {@code save()}: the consumer storing a vector has not touched
     * the entity itself.
     */
    @Override
    public void writeExternalVector(Class<?> entityType, Object entity, String vectorName) {
        UUID id = EntityReflection.readId(entity);
        EmbeddingVector vector = ((JavAIVectorizable) entity).externalVector(vectorName);
        String declaredModel = JavAIRuntime.externalVectorModel(entityType, vectorName);
        List<Bson> ops = new ArrayList<>();
        if (vector.isAbsent()) {
            String qualified = qualify(vectorName + "Vector", declaredModel);
            ops.add(Updates.unset(qualified));
            ops.add(Updates.unset(qualified + "ComputedAt"));
            ops.add(Updates.unset(qualified + "ComputedFor"));
        } else {
            String qualified = qualify(vectorName + "Vector", vector.modelId());
            ops.add(Updates.set(qualified, toDoubleList(vector.values())));
            ops.add(Updates.set(qualified + "ComputedAt", vector.computedAt().toString()));
            ops.add(Updates.set(qualified + "ComputedFor",
                    JavAIRuntime.externalVectorKey(entity, vectorName)));
        }
        collectionFor(entityType).updateOne(Filters.eq("_id", id.toString()), Updates.combine(ops));
    }

    /**
     * Restores each {@code @ExternalVector} from its declared model's document fields, together with the
     * content key it was written for (OMI-290).
     *
     * <p>The key is what makes the restored vector answerable: {@code externalVector()} compares it against
     * the entity's current content on every read, so a vector hydrated without one is held and never served
     * -- which from outside looks exactly like a pipeline that never ran.
     */
    private void hydrateExternalVectors(Class<?> entityType, Object entity, Document doc) {
        for (String vectorName : JavAIRuntime.externalVectorNames(entityType)) {
            String declaredModel = JavAIRuntime.externalVectorModel(entityType, vectorName);
            String qualified = qualify(vectorName + "Vector", declaredModel);
            if (!(doc.get(qualified) instanceof List<?> stored)) {
                continue;
            }
            float[] values = new float[stored.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ((Number) stored.get(i)).floatValue();
            }
            String computedAt = doc.getString(qualified + "ComputedAt");
            JavAIRuntime.hydrateExternalVector(entity, vectorName,
                    new EmbeddingVector(values, declaredModel, values.length,
                            computedAt == null ? Instant.now() : Instant.parse(computedAt)),
                    doc.getString(qualified + "ComputedFor"));
        }
    }

    @SuppressWarnings("unchecked")
    private void hydrateVectors(Class<?> entityType, Object entity, Document doc) {
        if (!(entity instanceof JavAIVectorizable)) {
            return;
        }
        // Read before the currentModelId() guard: an @ExternalVector's model is declared on the type, so it
        // is readable whether or not a text provider is configured or can name itself (OMI-290).
        hydrateExternalVectors(entityType, entity, doc);
        String modelId = JavAIRuntime.currentModelId();
        if (modelId == null) {
            return;
        }
        for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
            String qualified = qualify(fieldName + "Vector", modelId);
            Object raw = doc.get(qualified);
            if (!(raw instanceof List<?> stored)) {
                continue;
            }
            float[] values = new float[stored.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ((Number) stored.get(i)).floatValue();
            }
            String computedAt = doc.getString(qualified + "ComputedAt");
            JavAIRuntime.hydrateFieldVector(entity, fieldName, new EmbeddingVector(
                    values, modelId, values.length,
                    computedAt == null ? Instant.now() : Instant.parse(computedAt)));
        }

        // The concatenated text vector is a real embedding, not arithmetic over field vectors, so skipping
        // this would mean a live model call on every load of every participating entity (OMI-191).
        if (JavAIRuntime.participatesInConcatenation(entityType)
                && doc.get(qualify("concatenatedTextVector", modelId)) instanceof List<?> storedConcat) {
            String qualifiedConcat = qualify("concatenatedTextVector", modelId);
            float[] values = new float[storedConcat.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ((Number) storedConcat.get(i)).floatValue();
            }
            String computedAt = doc.getString(qualifiedConcat + "ComputedAt");
            JavAIRuntime.hydrateConcatenatedTextVector(entity, new EmbeddingVector(
                    values, modelId, values.length,
                    computedAt == null ? Instant.now() : Instant.parse(computedAt)));
        }
    }

    @SuppressWarnings("unchecked")
    private void hydrateReferenceField(Object owner, Field field, Object rawValue, Map<UUID, Object> hydrated) {
        if (rawValue == null) {
            return; // never-set singular reference; nothing stored
        }
        Class<?> fieldType = field.getType();
        if (Map.class.isAssignableFrom(fieldType)) {
            Map<String, Object> map = (Map<String, Object>) EntityReflection.readField(owner, field.getName());
            for (Document ref : sortedByOrdinal((List<Document>) rawValue)) {
                map.put(ref.getString("key"), hydrateReference(ref, hydrated));
            }
        } else if (Collection.class.isAssignableFrom(fieldType)) {
            Collection<Object> collection = (Collection<Object>) EntityReflection.readField(owner, field.getName());
            for (Document ref : sortedByOrdinal((List<Document>) rawValue)) {
                collection.add(hydrateReference(ref, hydrated));
            }
        } else {
            Object related = hydrateReference((Document) rawValue, hydrated);
            try {
                field.setAccessible(true);
                field.set(owner, related);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot write field " + field + " on " + owner.getClass(), e);
            }
        }
    }

    private static List<Document> sortedByOrdinal(List<Document> refs) {
        List<Document> sorted = new ArrayList<>(refs);
        sorted.sort((a, b) -> Integer.compare(a.getInteger("ordinal", 0), b.getInteger("ordinal", 0)));
        return sorted;
    }

    private Object hydrateReference(Document ref, Map<UUID, Object> hydrated) {
        UUID refId = UUID.fromString(ref.getString("id"));
        Object cached = hydrated.get(refId);
        if (cached != null) {
            return cached;
        }
        Class<?> relatedType;
        try {
            relatedType = Class.forName(ref.getString("type"));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot resolve persisted reference type " + ref.getString("type"), e);
        }
        Document relatedDoc = collectionFor(relatedType).find(Filters.eq("_id", refId.toString())).first();
        if (relatedDoc == null) {
            throw new IllegalStateException(
                    "Referenced " + relatedType.getName() + " " + refId + " not found in its own collection");
        }
        return hydrate(relatedType, relatedDoc, hydrated);
    }

    // ---- field <-> MongoDB value conversion ------------------------------------------------------

    private static boolean isIdField(Field field) {
        return field.isAnnotationPresent(jakarta.persistence.Id.class);
    }

    /** By declared type, not the runtime value -- so a currently-null singular reference is still correctly
     *  routed to the reference pass, not silently treated as a plain (null-valued) document field. Same
     *  rationale as {@code RepositoryBackendNeo4j.isRelationshipField}. */
    private static boolean isReferenceField(Field field) {
        Class<?> type = field.getType();
        return Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)
                || JavAIVectorizable.class.isAssignableFrom(type);
    }

    private static boolean isSimpleValue(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof UUID || value instanceof Enum<?> || value instanceof Instant;
    }

    private static Object toMongoValue(Object value) {
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return value;
    }

    private static void setFieldFromMongoValue(Object entity, Field field, Object value) {
        try {
            field.setAccessible(true);
            if (value == null) {
                field.set(entity, null);
                return;
            }
            Class<?> type = field.getType();
            if (Point.class.isAssignableFrom(type)) {
                Document geo = (Document) value;
                List<?> coordinates = (List<?>) geo.get("coordinates");
                field.set(entity, new Point(
                        ((Number) coordinates.get(0)).doubleValue(), ((Number) coordinates.get(1)).doubleValue()));
            } else if (type == String.class) {
                field.set(entity, (String) value);
            } else if (type == UUID.class) {
                field.set(entity, UUID.fromString((String) value));
            } else if (type == Instant.class) {
                field.set(entity, Instant.parse((String) value));
            } else if (type.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<Enum>) type, (String) value);
                field.set(entity, enumValue);
            } else if (type == int.class || type == Integer.class) {
                field.set(entity, ((Number) value).intValue());
            } else if (type == long.class || type == Long.class) {
                field.set(entity, ((Number) value).longValue());
            } else if (type == double.class || type == Double.class) {
                field.set(entity, ((Number) value).doubleValue());
            } else if (type == float.class || type == Float.class) {
                field.set(entity, ((Number) value).floatValue());
            } else if (type == boolean.class || type == Boolean.class) {
                field.set(entity, (Boolean) value);
            } else {
                field.set(entity, value);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write field " + field + " on " + entity.getClass(), e);
        }
    }

    private static List<Double> toDoubleList(float[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add((double) value);
        }
        return list;
    }

    private static String collectionName(Class<?> entityType) {
        return entityType.getSimpleName();
    }

    private MongoCollection<Document> collectionFor(Class<?> entityType) {
        return mongoTemplate().getCollection(collectionName(entityType));
    }

    /**
     * The {@code @Taggregate} containment of the registered model, over this backend's reference-pointer
     * arrays (OMI-304).
     *
     * <p>The <em>declaration</em> comes from {@link Containment}, exactly as on the other two backends; only
     * the traversal is native. A reference field is stored as {@code {type, id}} -- one such document for a
     * singular reference, an array of them for a collection -- and MongoDB's dot notation matches both
     * shapes with one filter, so {@code field.id} finds a container whether the field holds one member or
     * fifty. That is what makes this a query rather than two.
     */
    @Override
    public TaggregateContainment taggregateContainment() {
        return new TaggregateContainment() {
            @Override
            public boolean isEmpty() {
                return containment().hasNoTaggregates();
            }

            @Override
            public void containersOf(String childTypeName, UUID childId, BiConsumer<String, UUID> sink) {
                Class<?> childType = typeOrNull(childTypeName);
                for (Containment.Edge edge : containment().taggregateEdges()) {
                    // By name first, so a member type that was never registered as a repository of its own
                    // is still found through the edge that declares it; by assignability second, for a
                    // subclass held in a field declared as its supertype.
                    boolean holdsThisChild = edge.childType().getName().equals(childTypeName)
                            || (childType != null && edge.childType().isAssignableFrom(childType));
                    if (!holdsThisChild) {
                        continue;
                    }
                    ensureTaggregateIndex(edge);
                    for (Document doc : collectionFor(edge.parentType()).find(Filters.and(
                            Filters.eq(edge.fieldName() + ".type", childTypeName),
                            Filters.eq(edge.fieldName() + ".id", childId.toString())))) {
                        sink.accept(edge.parentType().getName(), UUID.fromString(doc.getString("_id")));
                    }
                }
            }

            @Override
            public void membersOf(String containerTypeName, UUID containerId, BiConsumer<String, UUID> sink) {
                Class<?> containerType = typeOrNull(containerTypeName);
                if (containerType == null) {
                    return;
                }
                Document container = collectionFor(containerType)
                        .find(Filters.eq("_id", containerId.toString())).first();
                if (container == null) {
                    return;
                }
                for (Containment.Edge edge : containment().taggregateEdges()) {
                    if (!edge.parentType().isAssignableFrom(containerType)) {
                        continue;
                    }
                    Object value = container.get(edge.fieldName());
                    if (value instanceof List<?> references) {
                        for (Object reference : references) {
                            acceptReference(reference, sink);
                        }
                    } else {
                        acceptReference(value, sink);
                    }
                }
            }

            @Override
            public void allContainers(BiConsumer<String, UUID> sink) {
                for (Class<?> containerType : containment().taggregateContainerTypes()) {
                    for (Document doc : collectionFor(containerType).find()) {
                        sink.accept(containerType.getName(), UUID.fromString(doc.getString("_id")));
                    }
                }
            }

            /** No ambient JDBC transaction on this backend -- see the Neo4j implementation's own note. */
            @Override
            public boolean inAmbientTransaction(ConnectionWork work) {
                return false;
            }

            /** No ambient transaction to hang a commit callback on either -- the caller drains inline. */
            @Override
            public boolean afterCommit(Runnable drain) {
                return false;
            }

            private void acceptReference(Object reference, BiConsumer<String, UUID> sink) {
                if (reference instanceof Document document
                        && document.getString("type") != null && document.getString("id") != null) {
                    sink.accept(document.getString("type"), UUID.fromString(document.getString("id")));
                }
            }
        };
    }

    /** One index per {@code @Taggregate} edge, on the reference id the container lookup filters by --
     *  without it, finding a member's containers is a collection scan per edge on every tag mutation. */
    private void ensureTaggregateIndex(Containment.Edge edge) {
        String key = edge.parentType().getName() + "#" + edge.fieldName();
        if (taggregateIndexesEnsured.add(key)) {
            collectionFor(edge.parentType())
                    .createIndex(Indexes.ascending(edge.fieldName() + ".type", edge.fieldName() + ".id"));
        }
    }

    private Class<?> typeOrNull(String typeName) {
        for (Class<?> registered : registeredEntityTypes) {
            if (registered.getName().equals(typeName)) {
                return registered;
            }
        }
        return null;
    }

    /** See the Postgres backend's own {@code containment()} for why this resolves lazily. */
    private Containment containment() {
        Containment resolved = containment;
        if (resolved == null) {
            synchronized (this) {
                resolved = containment;
                if (resolved == null) {
                    resolved = Containment.of(registeredEntityTypes);
                    containment = resolved;
                }
            }
        }
        return resolved;
    }

    // ---- lazy bootstrap -----------------------------------------------------------------------

    private MongoTemplate mongoTemplate() {
        MongoTemplate template = mongoTemplate;
        if (template != null) {
            return template;
        }
        synchronized (bootstrapLock) {
            if (mongoTemplate == null) {
                mongoTemplate = config.externalMongoTemplate() != null
                        ? config.externalMongoTemplate()
                        : buildMongoTemplate();
            }
            return mongoTemplate;
        }
    }

    private MongoTemplate buildMongoTemplate() {
        MongoClient client = MongoClients.create(config.mongoUri());
        MongoDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, config.mongoDatabase());
        return new MongoTemplate(factory);
    }
}
