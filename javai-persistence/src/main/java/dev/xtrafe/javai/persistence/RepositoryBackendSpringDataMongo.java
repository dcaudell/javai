package dev.xtrafe.javai.persistence;

import com.mongodb.MongoCommandException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.data.domain.Sort;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    private final JavAIPersistenceConfig config;
    private final Set<Class<?>> registeredEntityTypes = ConcurrentHashMap.newKeySet();
    private final Set<String> vectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final Object bootstrapLock = new Object();
    private volatile MongoTemplate mongoTemplate;

    RepositoryBackendSpringDataMongo(JavAIPersistenceConfig config) {
        this.config = config;
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
    public List<Object> findNearestByFieldVector(
            Class<?> entityType, String fieldName, EmbeddingVector reference, int limit) {
        String basePropertyName = fieldName.equals(COMBINED_VECTOR_FIELD) ? "vector" : fieldName + "Vector";
        return findNearest(entityType, basePropertyName, reference, limit);
    }

    @Override
    public List<Object> findNearestBySummaryVector(Class<?> entityType, EmbeddingVector reference, int limit) {
        return findNearest(entityType, "summaryVector", reference, limit);
    }

    private List<Object> findNearest(
            Class<?> entityType, String basePropertyName, EmbeddingVector reference, int limit) {
        String collectionName = collectionName(entityType);
        String qualifiedField = qualify(basePropertyName, reference.modelId());
        String indexName = vectorIndexName(collectionName, qualifiedField);
        ensureVectorIndex(collectionName, indexName, qualifiedField, reference.dims());

        List<Bson> pipeline = List.of(new Document("$vectorSearch", new Document()
                .append("index", indexName)
                .append("path", qualifiedField)
                .append("queryVector", toDoubleList(reference.values()))
                .append("numCandidates", Math.max(limit * 10, 100))
                .append("limit", limit)));

        Map<UUID, Object> hydrated = new HashMap<>();
        List<Object> results = new ArrayList<>();
        for (Document doc : collectionFor(entityType).aggregate(pipeline)) {
            results.add(hydrate(entityType, doc, hydrated));
        }
        return results;
    }

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
        Document definition = new Document("fields", List.of(new Document("type", "vector")
                .append("path", path)
                .append("numDimensions", dims)
                .append("similarity", "cosine")));
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
                if (!isSearchServiceNotYetReadyError(e) || Instant.now().isAfter(deadline)) {
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

    private static boolean isSearchServiceNotYetReadyError(MongoCommandException e) {
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
                if (!isSearchServiceNotYetReadyError(e)) {
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

    /** Rejects, at repository-creation time, a derived finder this backend can't translate: an unsupported
     *  operator (shared guard), or a nested path / reference-field filter. Related entities are stored as
     *  {@code {type, id}} reference pointers (never embedded), so filtering through them would need a
     *  {@code $lookup} join -- deliberately out of scope for this pass and tracked as the OMI-138 follow-up.
     *  This backend therefore supports filtering/sorting only on the entity's own simple document fields. */
    @Override
    public void validateDerivedQuery(Class<?> entityType, DerivedFinderQuery query) {
        query.assertCoreOperatorsOnly();
        for (Part part : query.partTree().getParts()) {
            validateSimpleFieldPath(entityType, part.getProperty().toDotPath());
        }
        for (Sort.Order order : query.partTree().getSort()) {
            validateSimpleFieldPath(entityType, order.getProperty());
        }
    }

    private static void validateSimpleFieldPath(Class<?> entityType, String dotPath) {
        if (dotPath.contains(".")) {
            throw new IllegalArgumentException("MongoDB derived finder cannot filter/sort through the nested path '"
                    + dotPath + "' on " + entityType.getName() + " -- related entities are stored as {type, id} "
                    + "reference pointers, not embedded, so a nested filter would need a $lookup join. That's the "
                    + "OMI-138 follow-up; filter on the entity's own fields for now.");
        }
        Field field = EntityReflection.findField(entityType, dotPath);
        if (isReferenceField(field)) {
            throw new IllegalArgumentException("MongoDB derived finder cannot filter/sort on '" + dotPath + "' of "
                    + entityType.getName() + " -- it's stored as a {type, id} reference pointer, not a plain field.");
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
        String field = mongoField(entityType, part.property().toDotPath());
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
            case IN -> Filters.in(field, mongoValues(a.get(0)));
            case NOT_IN -> Filters.nin(field, mongoValues(a.get(0)));
            case TRUE -> Filters.eq(field, true);
            case FALSE -> Filters.eq(field, false);
            default -> throw new IllegalArgumentException(
                    "Unsupported derived-query operator " + part.type() + " for the MongoDB backend.");
        };
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
            if (isSimpleValue(value)) {
                updates.put(fieldName, toMongoValue(value));
            }
            // else: not reference-shaped but also not a simple type -- documented Phase 0 boundary, skipped.
        }
        // Not every persisted @Entity is @JavAIVectorizable -- a @Taggable-only entity (no embedding of its
        // own; see javai-tagging's own doc/spec/tagging.md "Orthogonality" section) still gets its plain
        // fields written above, it just has no vector fields to add here.
        if (entity instanceof JavAIVectorizable vectorizable) {
            for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
                EmbeddingVector vector = vectorizable.fieldVector(fieldName);
                String qualified = qualify(fieldName + "Vector", vector.modelId());
                updates.put(qualified, toDoubleList(vector.values()));
                updates.put(qualified + "ComputedAt", vector.computedAt().toString());
            }
            EmbeddingVector combined = vectorizable.vector();
            String qualifiedCombined = qualify("vector", combined.modelId());
            updates.put(qualifiedCombined, toDoubleList(combined.values()));
            updates.put(qualifiedCombined + "ComputedAt", combined.computedAt().toString());

            EmbeddingVector summary = vectorizable.summaryVector();
            String qualifiedSummary = qualify("summaryVector", summary.modelId());
            updates.put(qualifiedSummary, toDoubleList(summary.values()));
            updates.put(qualifiedSummary + "ComputedAt", summary.computedAt().toString());
        }

        // $set-based upsert, deliberately never a whole-document replaceOne -- see this class's own javadoc
        // ("Writes are additive") for why a replace would destroy older models' already-written vectors.
        List<Bson> setOps = new ArrayList<>(updates.size());
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            setOps.add(Updates.set(entry.getKey(), entry.getValue()));
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
        return entity;
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
            if (type == String.class) {
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
