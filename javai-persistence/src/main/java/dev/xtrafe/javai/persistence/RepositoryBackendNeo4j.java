package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.collections.JavAIEdge;
import dev.xtrafe.javai.collections.JavAIGraphNode;
import dev.xtrafe.javai.collections.JavAIKnowledgeGraph;
import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.Ranked;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Query;
import org.neo4j.driver.SimpleQueryRunner;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Point;
import org.springframework.data.repository.query.parser.Part;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Neo4j backend: a reflective node/relationship mapper (no OGM dependency, consistent with this project's
 * general preference for reflection over an extra library where the logic is small), MERGE-based writes,
 * and native-vector-index-backed {@code findNearestBy*} queries.
 *
 * <p><b>Mapping rules</b> (the same "is this graph-shaped" boundary {@code JavAIRuntime.query()} already
 * draws -- and deliberately keyed off a field's *declared type*, not a {@code @Summary} annotation; see
 * below): the entity's simple class name is its node label (a Phase 0 assumption -- two distinct entity
 * types sharing a simple name across packages isn't supported); every {@code @Vectorize} field becomes a
 * {@code <field>Vector__<model>} property (plus a {@code <field>VectorComputedAt__<model>} sibling), the
 * object's own combined {@code vector()}/{@code summaryVector()} become {@code vector__<model>}/
 * {@code summaryVector__<model>} properties directly on the node (required -- Neo4j's native vector index
 * needs a direct node property, not a related node's); every field whose *declared type* is
 * {@code JavAIVectorizable}, a {@code Collection}, or a {@code Map} becomes a relationship (type name
 * upper-snake-cased from the field name) to a recursively-saved related node (or one per element), which
 * therefore needs its own {@code @Id}; every other simple-typed field (String/primitive/UUID/enum/Instant)
 * becomes a plain, unqualified property. Anything else is silently skipped, a documented Phase 0 boundary.
 *
 * <p><b>Relationship classification is by declared type, not the {@code @Summary} annotation</b> --
 * deliberately decoupled: {@code @Summary} means "contributes to {@code summaryVector()}'s decay-weighted
 * sum" (an in-memory, {@code javai-model} concern) and has nothing to do with whether a field should
 * persist as a relationship. A field can be graph-shaped without being {@code @Summary} (e.g.
 * {@code Article.draftComment}/{@code attachment}, {@code @SearchVisibility}-relevant but not summary-
 * contributing) and still needs a real relationship to round-trip through Neo4j at all -- conflating the
 * two would mean choosing between correct persistence and correct in-memory summary-vector semantics.
 *
 * <p><b>{@code Map} fields round-trip their keys too, via a relationship property.</b> A {@code Map<K, V>}
 * relationship field creates one relationship per entry, with the map key (stringified) stored as a
 * {@code mapKey} property on the relationship itself -- not on the target node, which may be reachable
 * through more than one owner/key. Hydration reads that property back and reconstructs the original map
 * ({@link #hydrateRelationshipField}), rather than only being able to correctly hydrate {@code Collection}
 * fields. {@code K} must be {@code String}; {@link #registerEntityType} validates this eagerly and throws a
 * clear {@code IllegalArgumentException} for any other key type, rather than silently storing a stringified
 * key that could never correctly round-trip back to its original type.
 *
 * <p><b>One property (and one vector index) per model, not one shared property.</b> {@code <model>} is
 * {@link ModelIds#sanitize} applied to {@code EmbeddingVector.modelId()} -- the same scheme
 * {@code RepositoryBackendHibernatePostgres} uses for its per-model tables, and for the same reason: two
 * different models' vectors are never comparable, so keeping them under physically separate names is the
 * correct model regardless of whether their dimensions happen to match. A useful side effect specific to
 * Neo4j: since a node's properties are schemaless, an older model's {@code <field>Vector__<oldModel>}
 * property is simply *never touched* once a newer model starts writing to its own, differently-named
 * property on the very same node -- there's no separate archival relationship/node type to maintain, the
 * old property sitting right there next to the new one *is* the history. Reverting the configured provider
 * needs no data migration at all: {@code findNearestBy*} resolves both the property name and the vector
 * index to query from the reference vector's own {@code modelId()}, so switching back immediately queries
 * whatever that model's property/index already holds.
 */
final class RepositoryBackendNeo4j implements RepositoryBackend {

    private static final System.Logger LOG = System.getLogger(RepositoryBackendNeo4j.class.getName());

    /** How long {@code db.awaitIndex} may block waiting for a freshly-created vector index to finish
     *  populating -- see {@link #awaitIndexOnline}. Paid once per index, by whichever caller creates it. */
    private static final int INDEX_ONLINE_TIMEOUT_SECONDS = 120;

    private final JavAIPersistenceConfig config;
    private final Set<String> vectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final Map<String, Class<?>> typesByLabel = new ConcurrentHashMap<>();

    /** See {@link #containment()} -- resolved on first use, never in the constructor. */
    private volatile Containment containment;
    private volatile Driver driver;

    RepositoryBackendNeo4j(JavAIPersistenceConfig config) {
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
        // Neo4j has no boot-time metadata to accumulate the way Hibernate does, but a label->Class
        // registry is still needed to hydrate a related node reached only via a @Summary relationship
        // traversal, where the target's Java type isn't otherwise known. Same "register everything you
        // need before using it" rule as the Postgres backend: a related entity type has to have its own
        // repository() call made at some point before traversal-hydration needs to resolve its label.
        validateMapKeyTypesAreSupported(entityType);
        validateNoAnyFields(entityType);
        typesByLabel.put(label(entityType), entityType);
    }

    /** Fails fast, at registration time, for a {@code Map} relationship field keyed by anything other than
     *  {@code String} -- the relationship's {@code mapKey} property is a plain string (see
     *  {@link #saveRelationship}), so a stringified non-{@code String} key could never correctly round-trip
     *  back to its original type on hydration. Mirrors {@code RepositoryBackendHibernatePostgres}'s own
     *  identical limitation/validation for the same reason. */
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
                throw new IllegalArgumentException("Neo4j persistence does not support @Any fields -- "
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
                throw new IllegalArgumentException("Neo4j persistence only supports String-keyed map fields -- "
                        + entityType.getName() + "." + field.getName() + " is keyed by "
                        + (keyType == null ? "an unresolvable type" : keyType.getName()));
            }
        }
    }

    /** {@code field}'s {@code index}-th generic type argument as a raw {@code Class}, or {@code null} if
     *  the field isn't parameterized or that argument isn't a simple class. */
    private static Class<?> genericTypeArgument(Field field, int index) {
        if (field.getGenericType() instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (index < args.length && args[index] instanceof Class<?> clazz) {
                return clazz;
            }
        }
        return null;
    }

    @Override
    public Object save(Class<?> entityType, Object entity) {
        // Same rationale as RepositoryBackendHibernatePostgres.save(): locks the whole reachable subgraph
        // and forces every vector read inside saveNode() to be accurate to the field values being written in
        // this same call, regardless of the ambient EmbeddingConsistencyMode.
        JavAIRuntime.runWithSubgraphLockedForPersistence(entity, () -> {
            try (Session session = driver().session()) {
                session.executeWrite(tx -> {
                    saveNode(tx, entity, new IdentityHashMap<>());
                    return null;
                });
            }
        });
        return entity;
    }

    /** Re-embeds every registered entity type, not just the repository's own -- see
     *  {@link RepositoryBackend#reindexAll} for why a datastore is re-indexed as a whole. */
    @Override
    public void reindexAll() {
        for (Class<?> registered : typesByLabel.values()) {
            reindexInChunks(registered, SummaryPolicy.RECOMPUTE_AFTER_COMMIT);
        }
    }

    @Override
    public Optional<Object> findById(Class<?> entityType, UUID id) {
        try (Session session = driver().session()) {
            Record record = session.executeRead(tx -> {
                var result = tx.run(new Query("MATCH (n:`" + label(entityType) + "` {id: $id}) RETURN n",
                        Values.parameters("id", id.toString())));
                return result.hasNext() ? result.single() : null;
            });
            if (record == null) {
                return Optional.empty();
            }
            return Optional.of(hydrate(session, entityType, record.get("n").asNode(), new HashMap<>()));
        }
    }

    @Override
    public long count(Class<?> entityType) {
        try (Session session = driver().session()) {
            return session.executeRead(tx -> tx
                    .run("MATCH (n:`" + label(entityType) + "`) RETURN count(n) AS c")
                    .single().get("c").asLong());
        }
    }

    @Override
    public List<Object> findAll(Class<?> entityType) {
        try (Session session = driver().session()) {
            List<Node> nodes = session.executeRead(tx -> {
                var result = tx.run("MATCH (n:`" + label(entityType) + "`) RETURN n");
                List<Node> found = new ArrayList<>();
                for (Record record : result.list()) {
                    found.add(record.get("n").asNode());
                }
                return found;
            });
            Map<UUID, Object> hydrated = new HashMap<>();
            List<Object> results = new ArrayList<>(nodes.size());
            for (Node node : nodes) {
                results.add(hydrate(session, entityType, node, hydrated));
            }
            return results;
        }
    }

    @Override
    public void deleteById(Class<?> entityType, UUID id) {
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n:`" + label(entityType) + "` {id: $id}) DETACH DELETE n",
                        Values.parameters("id", id.toString()));
                return null;
            });
        }
    }

    @Override
    public List<Ranked<Object>> findNearest(Class<?> entityType, NearestSpec spec) {
        validateNearestQuery(entityType, spec); // the builder idiom reaches here without a creation-time check
        // A summary search in a model this backend never wrote a property for still has an answer, and
        // returning nothing would be indistinguishable from "nothing is similar" (OMI-458). Folding is that
        // answer; the flag that makes it an indexed lookup is Postgres-only for now.
        spec.requireModelAgreement();
        requireConcatenatedTextInConfiguredModel(spec);
        if (foldsSummaryInMemory(containment(), entityType, spec)) {
            return foldNearestBySummary(entityType, spec);
        }
        return findNearest(entityType, vectorPropertyName(spec), spec);
    }

    /** Every kind is one node property here -- the grain problem that made Postgres' field table the wrong
     *  home for the entity-grain vectors does not arise on a store whose vectors are node properties. */
    private static String vectorPropertyName(NearestSpec spec) {
        return switch (spec.kind()) {
            case SUMMARY -> "summaryVector";
            case CONCATENATED_TEXT -> "concatenatedTextVector";
            case COMBINED -> "vector";
            case FIELD -> spec.fieldName() + "Vector";
        };
    }

    /**
     * Refuses a <b>narrowed</b> vector search, at repository-creation time (OMI-230).
     *
     * <p>Not a gap waiting to be filled -- a property of the store. {@code db.index.vector.queryNodes} is a
     * top-K call: it chooses its K nearest nodes and only then can Cypher see them, so a predicate can only
     * ever be applied to what the index already picked. That leaves two dishonest options and no honest one.
     * Filtering after the fact answers a different question than the one asked ("which of the nearest K
     * match" rather than "the nearest K that match"), and returns fewer than K whenever the predicate is
     * selective -- which is exactly the over-fetch-and-discard this feature exists to remove, merely moved
     * inside the library where the caller can no longer see it happening. Over-fetching by some multiple
     * only moves the same failure further out and makes it data-dependent.
     *
     * <p>So Neo4j says so. The unnarrowed search, {@link Ranked} similarities, and paging all work here --
     * only narrowing refuses, and it refuses when the repository is created rather than on the call that
     * needed it.
     */
    @Override
    public void validateNearestQuery(Class<?> entityType, NearestSpec spec) {
        if (!spec.isNarrowed()) {
            return;
        }
        throw new IllegalArgumentException("The Neo4j backend cannot narrow a vector search by a relational "
                + "predicate (on " + entityType.getName() + "). Its vector index answers only 'the K nearest "
                + "nodes', so a predicate could be applied only after K was already chosen -- which would "
                + "return fewer than the requested limit whenever the predicate excludes anything, and would "
                + "silently answer a different question than the one asked. Options: use the Postgres or "
                + "MongoDB backend for this query; run the unnarrowed search and filter in the caller, "
                + "accepting the over-fetch explicitly; or narrow with an ordinary derived finder and rank "
                + "in memory via JavAIVectorizable.query(...).");
    }

    private List<Ranked<Object>> findNearest(
            Class<?> entityType, String basePropertyName, NearestSpec spec) {
        EmbeddingVector reference = spec.reference();
        String label = label(entityType);
        String property = qualify(basePropertyName, reference.modelId());
        // ⚠️ **Nothing carries this property, so there is nothing to index and nothing to find.** Creating a
        // vector index here anyway -- which this did -- left a permanent, empty index behind for every
        // search whose reference named a model no node had ever been written in, and blocked the caller
        // while waiting for that junk index to come online. Unlike Postgres, whose write path provisions
        // its own tables, a query is the only thing that ever creates an index here, so this cannot simply
        // stop creating: it creates when there is something to create it for.
        if (!anyNodeCarries(label, property)) {
            return List.of();
        }
        ensureVectorIndex(label, property, reference.dims());
        String indexName = vectorIndexName(label, property);
        try (Session session = driver().session()) {
            // limit + offset, then drop the head: ranking is total, so skipping it is exact -- unlike
            // narrowing, paging needs nothing from the index that it does not already provide.
            int fetch = spec.limitIncludingOffset();
            List<Scored> scored = session.executeRead(tx -> {
                var result = tx.run("CALL db.index.vector.queryNodes($indexName, $limit, $reference) "
                                + "YIELD node, score RETURN node, score",
                        Values.parameters("indexName", indexName, "limit", fetch, "reference", reference.values()));
                List<Scored> found = new ArrayList<>();
                for (Record record : result.list()) {
                    found.add(new Scored(record.get("node").asNode(), record.get("score").asDouble()));
                }
                return found;
            });
            Map<UUID, Object> hydrated = new HashMap<>();
            List<Ranked<Object>> results = new ArrayList<>();
            for (int i = spec.offset(); i < scored.size(); i++) {
                Scored hit = scored.get(i);
                // Neo4j reports a cosine index's score rescaled into (0, 1] as (1 + cosine) / 2; Ranked
                // speaks raw cosine, like the rest of JavAI, so undo the rescaling here where it is known.
                results.add(new Ranked<>(
                        hydrate(session, entityType, hit.node(), hydrated), 2.0 * hit.score() - 1.0));
            }
            return results;
        }
    }

    private record Scored(Node node, double score) {
    }

    /** Whether a single node of {@code label} carries {@code property} at all -- one indexed-free lookup
     *  bounded by {@code LIMIT 1}, which is all "is there anything to index" needs to ask. */
    private boolean anyNodeCarries(String label, String property) {
        try (Session session = driver().session()) {
            return session.executeRead(tx -> tx.run(
                    "MATCH (n:`" + label + "`) WHERE n.`" + property + "` IS NOT NULL RETURN n LIMIT 1")
                    .hasNext());
        }
    }

    private void ensureVectorIndex(String label, String property, int dims) {
        String key = label + "." + property;
        if (!vectorIndexesEnsured.add(key)) {
            return;
        }
        String indexName = vectorIndexName(label, property);
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE VECTOR INDEX `" + indexName + "` IF NOT EXISTS "
                        + "FOR (n:`" + label + "`) ON n.`" + property + "` "
                        + "OPTIONS {indexConfig: {`vector.dimensions`: $dims, `vector.similarity_function`: 'cosine'}}",
                        Values.parameters("dims", dims));
                return null;
            });
            awaitIndexOnline(session, indexName);
        }
    }

    /**
     * Blocks until {@code indexName} is actually usable, rather than merely created.
     *
     * <p>A Neo4j index is populated <b>asynchronously</b>: {@code CREATE VECTOR INDEX} returns as soon as the
     * index exists, in state {@code POPULATING}, and a query issued against it before it reaches
     * {@code ONLINE} is answered from a partially-built index -- silently, with no error, just fewer or
     * wrongly-ordered results. On a warm database nothing notices, because the index was built during some
     * earlier run; on a fresh one it produces a nearest-neighbour search where a node is not its own nearest
     * neighbour. That is exactly what {@code PersistenceE2ETest.neo4jFindNearestByFieldVectorRanksByRealSimilarity}
     * hit on a newly-created container, and it reproduced on a build predating any of this ticket's changes.
     *
     * <p>This is the same readiness problem {@code RepositoryBackendSpringDataMongo} already solves by polling
     * {@code listSearchIndexes()} for {@code queryable: true}, and it is solved the same way here -- with
     * Neo4j's own {@code db.awaitIndex}, which exists for precisely this. Only the creating call pays the
     * wait, since {@link #vectorIndexesEnsured} admits one caller per index.
     *
     * <p>A failure is swallowed deliberately. {@code db.awaitIndex} throws when the index does not come online
     * inside its timeout, and on a very large store that is a slow index rather than a broken one; refusing
     * the write in that case would be worse than proceeding, since the index will finish on its own and the
     * only cost meanwhile is the imprecise ranking this method exists to avoid.
     */
    private static void awaitIndexOnline(Session session, String indexName) {
        try {
            session.executeWrite(tx -> {
                tx.run("CALL db.awaitIndex($name, $timeout)",
                        Values.parameters("name", indexName, "timeout", INDEX_ONLINE_TIMEOUT_SECONDS));
                return null;
            });
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Vector index '" + indexName + "' did not come online "
                    + "within " + INDEX_ONLINE_TIMEOUT_SECONDS + "s (" + e.getMessage() + "). Continuing: it "
                    + "will finish populating on its own, but similarity searches against it may rank "
                    + "imprecisely until it does.");
        }
    }

    private static String vectorIndexName(String label, String qualifiedProperty) {
        return "javai_" + label.toLowerCase(java.util.Locale.ROOT) + "_" + qualifiedProperty;
    }

    private static String qualify(String basePropertyName, String modelId) {
        return basePropertyName + "__" + ModelIds.sanitize(modelId);
    }

    // ---- ordinary derived finders (OMI-138): Cypher translation --------------------------------

    /** Rejects, at repository-creation time, a derived finder this backend can't translate. Nested filter
     *  paths traverse relationships of <em>any</em> cardinality now (singular or to-many, via {@code EXISTS {}}
     *  subqueries), so the only intermediate rejection is a {@code KnowledgeGraph} field (its two-relationship
     *  encoding isn't a plain traversal). Leaf rules depend on the operator: emptiness ({@code IsEmpty}/
     *  {@code IsNotEmpty}) needs a collection/map field; geo ({@code Near}/{@code Within}) needs a
     *  {@code Point} field; every other operator needs a scalar property. Sort is limited to a root scalar
     *  property (an {@code EXISTS}-scoped var can't drive {@code ORDER BY}). */
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
            boolean leaf = i == segments.length - 1;
            if (!leaf) {
                if (KnowledgeGraph.class.isAssignableFrom(field.getType()) || !isRelationshipField(field)) {
                    throw new IllegalArgumentException("Neo4j derived finder cannot traverse '" + segments[i]
                            + "' on " + owner.getName() + " -- an intermediate path segment must be a relationship "
                            + "field (singular, Collection, or Map), and not a KnowledgeGraph.");
                }
                owner = DerivedFinderQuery.isToMany(field)
                        ? DerivedFinderQuery.collectionMemberType(field) : field.getType();
            } else {
                validateLeaf(owner, field, type);
            }
        }
    }

    private static void validateLeaf(Class<?> owner, Field field, Part.Type type) {
        boolean collection = DerivedFinderQuery.isToMany(field);
        switch (type) {
            case IS_EMPTY, IS_NOT_EMPTY -> {
                if (!collection) {
                    throw new IllegalArgumentException("Neo4j IsEmpty/IsNotEmpty needs a Collection/Map field -- '"
                            + field.getName() + "' on " + owner.getName() + " is not one.");
                }
            }
            case NEAR, WITHIN -> {
                if (!Point.class.isAssignableFrom(field.getType())) {
                    throw new IllegalArgumentException("Neo4j Near/Within needs a Point field -- '" + field.getName()
                            + "' on " + owner.getName() + " is " + field.getType().getSimpleName() + ".");
                }
            }
            case EXISTS -> {
                // Property presence: valid on any field (scalar -> IS NOT NULL, relationship -> non-empty).
            }
            default -> {
                if (isRelationshipField(field)) {
                    throw new IllegalArgumentException("Neo4j derived finder cannot filter on '" + field.getName()
                            + "' of " + owner.getName() + " with " + type + " -- it maps to a relationship, not a "
                            + "scalar node property.");
                }
            }
        }
    }

    private static void validateSortPath(Class<?> entityType, String dotPath) {
        if (dotPath.contains(".")) {
            throw new IllegalArgumentException("Neo4j derived finder can only sort by a root scalar property, not the "
                    + "nested path '" + dotPath + "' on " + entityType.getName() + ".");
        }
        Field field = EntityReflection.findField(entityType, dotPath);
        if (isRelationshipField(field)) {
            throw new IllegalArgumentException("Neo4j derived finder cannot sort by '" + dotPath + "' of "
                    + entityType.getName() + " -- it maps to a relationship, not a scalar node property.");
        }
    }

    @Override
    public List<Object> findByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args,
            DerivedFinderQuery.Constraints constraints) {
        Cypher cypher = new Cypher(label(entityType), entityType);
        String where = cypher.buildWhere(query.boundOrGroups(args));
        List<String> orderBy = cypher.buildOrderBy(constraints.sort());
        StringBuilder sql = new StringBuilder(cypher.matchClause());
        if (where != null) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" RETURN DISTINCT n");
        if (!orderBy.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orderBy));
        }
        if (constraints.skip() != null) {
            sql.append(" SKIP ").append(constraints.skip());
        }
        if (constraints.maxResults() != null) {
            sql.append(" LIMIT ").append(constraints.maxResults());
        }
        try (Session session = driver().session()) {
            List<Node> nodes = session.executeRead(tx -> {
                var result = tx.run(new Query(sql.toString(), cypher.params()));
                List<Node> found = new ArrayList<>();
                for (Record record : result.list()) {
                    found.add(record.get("n").asNode());
                }
                return found;
            });
            Map<UUID, Object> hydrated = new HashMap<>();
            List<Object> results = new ArrayList<>(nodes.size());
            for (Node node : nodes) {
                results.add(hydrate(session, entityType, node, hydrated));
            }
            return results;
        }
    }

    @Override
    public long countByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        Cypher cypher = new Cypher(label(entityType), entityType);
        String where = cypher.buildWhere(query.boundOrGroups(args));
        StringBuilder sql = new StringBuilder(cypher.matchClause());
        if (where != null) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" RETURN count(DISTINCT n) AS c");
        try (Session session = driver().session()) {
            return session.executeRead(tx -> tx.run(new Query(sql.toString(), cypher.params())).single().get("c").asLong());
        }
    }

    @Override
    public boolean existsByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        Cypher cypher = new Cypher(label(entityType), entityType);
        String where = cypher.buildWhere(query.boundOrGroups(args));
        StringBuilder sql = new StringBuilder(cypher.matchClause());
        if (where != null) {
            sql.append(" WHERE ").append(where);
        }
        sql.append(" RETURN n LIMIT 1");
        try (Session session = driver().session()) {
            return session.executeRead(tx -> tx.run(new Query(sql.toString(), cypher.params())).hasNext());
        }
    }

    @Override
    public long deleteByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        long matched = countByDerivedQuery(entityType, query, args);
        Cypher cypher = new Cypher(label(entityType), entityType);
        String where = cypher.buildWhere(query.boundOrGroups(args));
        StringBuilder sql = new StringBuilder(cypher.matchClause());
        if (where != null) {
            sql.append(" WHERE ").append(where);
        }
        // DETACH DELETE, mirroring deleteById -- removes the node and its relationships (vectors are node
        // properties, gone with it), but not related nodes, the same documented Neo4j boundary.
        sql.append(" DETACH DELETE n");
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                tx.run(new Query(sql.toString(), cypher.params()));
                return null;
            });
        }
        return matched;
    }

    /** Builds a Cypher WHERE/ORDER BY (and its parameter map) from a {@link DerivedFinderQuery}'s bound
     *  predicate. Nested paths become self-contained {@code EXISTS { MATCH (n)-[:REL]->(x) WHERE ... }}
     *  subqueries -- correct through relationships of any cardinality and composing safely under
     *  {@code AND}/{@code OR}, unlike a shared top-level {@code MATCH} pattern would. Every bound value is
     *  routed through {@link RepositoryBackendNeo4j#toNeo4jValue}, so a {@code UUID}/{@code Instant}/
     *  {@code Enum} argument is compared against the same string form it was stored as. */
    private static final class Cypher {

        private final String label;
        private final Class<?> rootType;
        private final Map<String, Object> params = new HashMap<>();
        private int variableCounter;
        private int parameterCounter;

        Cypher(String label, Class<?> rootType) {
            this.label = label;
            this.rootType = rootType;
        }

        String matchClause() {
            return "MATCH (n:`" + label + "`)";
        }

        Map<String, Object> params() {
            return params;
        }

        String buildWhere(List<List<DerivedFinderQuery.BoundPart>> orGroups) {
            List<String> orClauses = new ArrayList<>();
            for (List<DerivedFinderQuery.BoundPart> group : orGroups) {
                List<String> andClauses = new ArrayList<>();
                for (DerivedFinderQuery.BoundPart part : group) {
                    andClauses.add(condition(part));
                }
                if (!andClauses.isEmpty()) {
                    orClauses.add("(" + String.join(" AND ", andClauses) + ")");
                }
            }
            return orClauses.isEmpty() ? null : String.join(" OR ", orClauses);
        }

        List<String> buildOrderBy(Sort sort) {
            List<String> orders = new ArrayList<>();
            if (sort == null || sort.isUnsorted()) {
                return orders;
            }
            for (Sort.Order order : sort) {
                String expression = "n.`" + order.getProperty() + "`"; // validated: root scalar only
                if (order.isIgnoreCase()) {
                    expression = "toLower(" + expression + ")";
                }
                orders.add(expression + (order.isAscending() ? " ASC" : " DESC"));
            }
            return orders;
        }

        /** The relationship-hop pattern from {@code n} down to (but not including) the leaf segment, plus the
         *  variable + entity type of the node the leaf lives on. */
        private record Hops(String pattern, String ownerVariable, Class<?> ownerType) {
        }

        private Hops walkHops(String[] segments) {
            StringBuilder pattern = new StringBuilder();
            String variable = "n";
            Class<?> type = rootType;
            for (int i = 0; i < segments.length - 1; i++) {
                Field field = EntityReflection.findField(type, segments[i]);
                String next = "e" + (variableCounter++);
                pattern.append("-[:`").append(relationshipType(segments[i])).append("`]->(").append(next).append(")");
                variable = next;
                type = DerivedFinderQuery.isToMany(field)
                        ? DerivedFinderQuery.collectionMemberType(field) : field.getType();
            }
            return new Hops(pattern.toString(), variable, type);
        }

        private String condition(DerivedFinderQuery.BoundPart part) {
            String[] segments = part.property().toDotPath().split("\\.");
            Hops hops = walkHops(segments);
            boolean nested = segments.length > 1;
            String leaf = segments[segments.length - 1];
            Part.Type type = part.type();

            // Collection-shaped leaves: emptiness (and EXISTS on a collection) test a final relationship hop.
            boolean collectionLeaf = DerivedFinderQuery.isToMany(EntityReflection.findField(hops.ownerType(), leaf));
            if (type == Part.Type.IS_EMPTY || type == Part.Type.IS_NOT_EMPTY
                    || (type == Part.Type.EXISTS && collectionLeaf)) {
                String exists = "EXISTS { MATCH (n)" + hops.pattern()
                        + "-[:`" + relationshipType(leaf) + "`]->() }";
                return type == Part.Type.IS_EMPTY ? "NOT " + exists : exists;
            }
            if (type == Part.Type.NEAR || type == Part.Type.WITHIN) {
                DerivedFinderQuery.GeoCircle geo = DerivedFinderQuery.geoCircle(part);
                String cond = "point.distance(" + hops.ownerVariable() + ".`" + leaf + "`, "
                        + "point({longitude: " + param(geo.longitude()) + ", latitude: " + param(geo.latitude())
                        + "})) <= " + param(geo.radiusMeters());
                return wrap(nested, hops, cond);
            }
            return wrap(nested, hops, scalarCondition(hops.ownerVariable(), leaf, part));
        }

        /** Wraps a leaf condition in an {@code EXISTS { MATCH ... WHERE cond }} when the path is nested; a
         *  root-level condition needs no wrapper. */
        private String wrap(boolean nested, Hops hops, String cond) {
            return nested ? "EXISTS { MATCH (n)" + hops.pattern() + " WHERE " + cond + " }" : cond;
        }

        private String scalarCondition(String ownerVariable, String leaf, DerivedFinderQuery.BoundPart part) {
            String raw = ownerVariable + ".`" + leaf + "`";
            boolean ic = part.ignoreCase();
            String lhs = ic ? "toLower(" + raw + ")" : raw;
            List<Object> a = part.arguments();
            return switch (part.type()) {
                case SIMPLE_PROPERTY -> lhs + " = " + param(value(a.get(0), ic));
                case NEGATING_SIMPLE_PROPERTY -> lhs + " <> " + param(value(a.get(0), ic));
                case GREATER_THAN, AFTER -> lhs + " > " + param(value(a.get(0), ic));
                case GREATER_THAN_EQUAL -> lhs + " >= " + param(value(a.get(0), ic));
                case LESS_THAN, BEFORE -> lhs + " < " + param(value(a.get(0), ic));
                case LESS_THAN_EQUAL -> lhs + " <= " + param(value(a.get(0), ic));
                case BETWEEN -> "(" + lhs + " >= " + param(value(a.get(0), ic))
                        + " AND " + lhs + " <= " + param(value(a.get(1), ic)) + ")";
                case IS_NULL -> raw + " IS NULL";
                case IS_NOT_NULL, EXISTS -> raw + " IS NOT NULL";
                case STARTING_WITH -> lhs + " STARTS WITH " + param(stringValue(a.get(0), ic));
                case ENDING_WITH -> lhs + " ENDS WITH " + param(stringValue(a.get(0), ic));
                case CONTAINING -> lhs + " CONTAINS " + param(stringValue(a.get(0), ic));
                case NOT_CONTAINING -> "NOT (" + lhs + " CONTAINS " + param(stringValue(a.get(0), ic)) + ")";
                case LIKE -> lhs + " =~ " + param(likeToRegex(String.valueOf(a.get(0)), ic));
                case NOT_LIKE -> "NOT (" + lhs + " =~ " + param(likeToRegex(String.valueOf(a.get(0)), ic)) + ")";
                case REGEX -> lhs + " =~ " + param((ic ? "(?i)" : "") + String.valueOf(a.get(0)));
                case IN -> lhs + " IN " + param(listValue(a.get(0), ic));
                case NOT_IN -> "NOT (" + lhs + " IN " + param(listValue(a.get(0), ic)) + ")";
                case TRUE -> raw + " = true";
                case FALSE -> raw + " = false";
                default -> throw new IllegalArgumentException(
                        "Unsupported derived-query operator " + part.type() + " for the Neo4j backend.");
            };
        }

        private String param(Object value) {
            String name = "p" + (parameterCounter++);
            params.put(name, value);
            return "$" + name;
        }

        private static Object value(Object raw, boolean ignoreCase) {
            Object converted = toNeo4jValue(raw);
            return ignoreCase && converted instanceof String s ? s.toLowerCase(java.util.Locale.ROOT) : converted;
        }

        private static String stringValue(Object raw, boolean ignoreCase) {
            String s = String.valueOf(toNeo4jValue(raw));
            return ignoreCase ? s.toLowerCase(java.util.Locale.ROOT) : s;
        }

        private static List<Object> listValue(Object raw, boolean ignoreCase) {
            List<Object> converted = new ArrayList<>();
            if (raw instanceof Collection<?> collection) {
                for (Object element : collection) {
                    converted.add(value(element, ignoreCase));
                }
            }
            return converted;
        }

        /** SQL-LIKE ({@code %}/{@code _}) to a Cypher regex ({@code =~}), escaping other regex metacharacters
         *  so a literal dot or bracket in the pattern stays literal. (The {@code Regex} operator, by contrast,
         *  binds its argument as an already-formed regex.) */
        private static String likeToRegex(String pattern, boolean ignoreCase) {
            StringBuilder regex = new StringBuilder(ignoreCase ? "(?i)" : "");
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
    }

    // ---- save: reflective node + relationship mapping ------------------------------------------

    private void saveNode(SimpleQueryRunner tx, Object entity, Map<Object, UUID> alreadySaved) {
        if (alreadySaved.containsKey(entity)) {
            return;
        }
        // Assigned here, not just in save()'s top-level entry point: a @Summary-referenced entity (e.g.
        // Article.featuredComment) reaches this same method recursively via saveRelationship(), and a
        // freshly-constructed related object has never had save() called on it directly, so its own @Id
        // is still null at this point just as often as the top-level entity's is.
        if (EntityReflection.readId(entity) == null) {
            EntityReflection.writeId(entity, UUID.randomUUID());
        }
        UUID id = EntityReflection.readId(entity);
        alreadySaved.put(entity, id);

        Class<?> entityType = entity.getClass();
        String label = label(entityType);

        Map<String, Object> properties = new HashMap<>();
        for (Field field : EntityReflection.allFields(entityType)) {
            String fieldName = field.getName();
            if (isIdField(field) || isRelationshipField(field)) {
                continue; // id is the MERGE key, handled separately; relationships handled in the pass below
            }
            Object value = EntityReflection.readField(entity, fieldName);
            if (value instanceof Point point) {
                // A geo Point stores as a native Neo4j WGS-84 point property, so point.distance(...) geo
                // finders (Near/Within) work against it directly. srid 4326: x = longitude, y = latitude.
                properties.put(fieldName, Values.point(4326, point.getX(), point.getY()));
            } else if (isSimpleValue(value)) {
                properties.put(fieldName, toNeo4jValue(value));
            }
            // else: not graph-shaped but also not a simple type -- documented Phase 0 boundary, skipped.
        }
        // Not every persisted @Entity is @JavAIVectorizable -- a @Taggable-only entity (no embedding of its
        // own; see javai-tagging's own doc/spec/tagging.md "Orthogonality" section) still gets a real node
        // with its plain properties above, it just has no vector properties to add here.
        if (entity instanceof JavAIVectorizable vectorizable) {
            // An absent vector (EmbeddingVector.absent(), OMI-187) carries no content and no dimensions, so
            // it gets no property rather than an empty array under a synthetic "<absent>" model id -- and
            // any property a previous save wrote is removed, so a field that loses its content cannot keep
            // matching vector searches for content it no longer has. A null in a `SET n += $props` map is
            // Cypher's property removal, so this needs no separate REMOVE clause.
            String currentModelId = JavAIRuntime.currentModelId();
            for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
                EmbeddingVector vector = vectorizable.fieldVector(fieldName);
                if (vector.isAbsent()) {
                    clearVectorProperty(properties, fieldName + "Vector", currentModelId);
                    continue;
                }
                String qualified = qualify(fieldName + "Vector", vector.modelId());
                properties.put(qualified, vector.values());
                properties.put(qualified + "ComputedAt", vector.computedAt().toString());
            }
            EmbeddingVector combined = vectorizable.vector();
            if (combined.isAbsent()) {
                clearVectorProperty(properties, "vector", currentModelId);
            } else {
                String qualifiedCombined = qualify("vector", combined.modelId());
                properties.put(qualifiedCombined, combined.values());
                properties.put(qualifiedCombined + "ComputedAt", combined.computedAt().toString());
            }

            EmbeddingVector summary = vectorizable.summaryVector();
            if (summary.isAbsent()) {
                clearVectorProperty(properties, "summaryVector", currentModelId);
            } else {
                String qualifiedSummary = qualify("summaryVector", summary.modelId());
                properties.put(qualifiedSummary, summary.values());
                properties.put(qualifiedSummary + "ComputedAt", summary.computedAt().toString());
            }

            // Concatenated text and its vector (OMI-191). No grain problem here, unlike Postgres: these are
            // per-entity properties on the node, exactly like summaryVector above. The text itself is stored
            // alongside so re-embedding under another model needs no walk of the object graph. Nulling both
            // when concatenation is switched off is what stops a stale text vector outliving the opt-in.
            EmbeddingVector concatenated = vectorizable.concatenatedTextVector();
            if (concatenated.isAbsent()) {
                clearVectorProperty(properties, "concatenatedTextVector", currentModelId);
                if (currentModelId != null) {
                    properties.put(qualify("concatenatedText", currentModelId), null);
                }
            } else {
                String qualifiedConcat = qualify("concatenatedTextVector", concatenated.modelId());
                properties.put(qualifiedConcat, concatenated.values());
                properties.put(qualifiedConcat + "ComputedAt", concatenated.computedAt().toString());
                // A null text removes the property, which is exactly right: concatenatedText() returns
                // null for "there is no text", and Neo4j has no separate unset step to make.
                properties.put(qualify("concatenatedText", concatenated.modelId()),
                        vectorizable.concatenatedText());
            }

            // @ExternalVector properties (OMI-290). Same per-entity shape as summaryVector above, with two
            // differences: the qualifier is the *declared* model rather than the configured one -- these
            // vectors have nothing to do with whichever text provider is running -- and the content key the
            // vector was computed for travels alongside it. Without that key a hydrated vector is held and
            // never served, since every read compares it against the entity's current content.
            for (String vectorName : JavAIRuntime.externalVectorNames(entityType)) {
                String declaredModel = JavAIRuntime.externalVectorModel(entityType, vectorName);
                EmbeddingVector external = vectorizable.externalVector(vectorName);
                if (external.isAbsent()) {
                    // Nothing supplied yet, or superseded. Removing rather than leaving it is the same rule
                    // an absent @Vectorize field follows, and matters more: the stored vector confidently
                    // describes content this node no longer references.
                    clearVectorProperty(properties, vectorName + "Vector", declaredModel);
                    properties.put(qualify(vectorName + "Vector", declaredModel) + "ComputedFor", null);
                    continue;
                }
                String qualifiedExternal = qualify(vectorName + "Vector", external.modelId());
                properties.put(qualifiedExternal, external.values());
                properties.put(qualifiedExternal + "ComputedAt", external.computedAt().toString());
                properties.put(qualifiedExternal + "ComputedFor",
                        JavAIRuntime.externalVectorKey(entity, vectorName));
            }
        }

        tx.run("MERGE (n:`" + label + "` {id: $id}) SET n += $props",
                Values.parameters("id", id.toString(), "props", properties));

        for (Field field : EntityReflection.allFields(entityType)) {
            if (!isRelationshipField(field)) {
                continue;
            }
            String fieldName = field.getName();
            Object value = EntityReflection.readField(entity, fieldName);
            if (value instanceof KnowledgeGraph<?, ?> graph) {
                saveKnowledgeGraphField(tx, label, id, fieldName, graph, alreadySaved);
                continue;
            }
            String relationshipType = relationshipType(fieldName);
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                    saveRelationship(tx, label, id, mapEntry.getValue(), relationshipType, alreadySaved,
                            String.valueOf(mapEntry.getKey()));
                }
            } else if (value instanceof Iterable<?> iterable) {
                for (Object element : iterable) {
                    saveRelationship(tx, label, id, element, relationshipType, alreadySaved, null);
                }
            } else if (value != null) {
                saveRelationship(tx, label, id, value, relationshipType, alreadySaved, null);
            }
        }
    }

    /** {@code mapKey} is {@code null} for a singular reference or a {@code Collection} element, or the
     *  original {@code Map} key ({@code String}-typed -- see {@link #validateMapKeyTypesAreSupported}) for
     *  a {@code Map} value -- stored as a property on the relationship itself (not the target node, which
     *  may be reachable through more than one owner/key) so hydration can reconstruct the original map. */
    private void saveRelationship(SimpleQueryRunner tx, String ownerLabel, UUID ownerId, Object target,
            String relationshipType, Map<Object, UUID> alreadySaved, String mapKey) {
        saveNode(tx, target, alreadySaved); // ensures the target node exists; no-ops if already visited
        UUID targetId = EntityReflection.readId(target);
        String targetLabel = label(target.getClass());
        tx.run("MATCH (a:`" + ownerLabel + "` {id: $ownerId}), (b:`" + targetLabel + "` {id: $targetId}) "
                + "MERGE (a)-[r:`" + relationshipType + "`]->(b) SET r.mapKey = $mapKey",
                Values.parameters("ownerId", ownerId.toString(), "targetId", targetId.toString(), "mapKey", mapKey));
    }

    /** A {@code KnowledgeGraph}-typed field is neither a {@code Map}/{@code Collection} of related entities
     *  nor a singular reference -- it owns its own internal node membership and edges, so it needs two
     *  field-name-scoped relationship types rather than the one {@link #relationshipType} yields: {@code
     *  <FIELD>_MEMBER} (owner to node, so an isolated node with no edges still round-trips, and so hydration
     *  can tell which nodes belong to *this* field/owner even if the same node also appears in some other
     *  KnowledgeGraph field elsewhere) and {@code <FIELD>_EDGE} (node to node, the graph's own edges). See
     *  {@link #saveGraphEdge} for why edges MERGE on their full property set rather than bare-pattern. */
    private <N extends JavAIGraphNode, E extends JavAIEdge> void saveKnowledgeGraphField(
            SimpleQueryRunner tx, String ownerLabel, UUID ownerId, String fieldName,
            KnowledgeGraph<N, E> graph, Map<Object, UUID> alreadySaved) {
        String memberType = relationshipType(fieldName) + "_MEMBER";
        String edgeType = relationshipType(fieldName) + "_EDGE";
        for (N node : graph.nodes()) {
            saveNode(tx, node, alreadySaved);
            UUID nodeId = EntityReflection.readId(node);
            String nodeLabel = label(node.getClass());
            tx.run(new Query("MATCH (owner:`" + ownerLabel + "` {id: $ownerId}), (n:`" + nodeLabel + "` {id: $nodeId}) "
                            + "MERGE (owner)-[:`" + memberType + "`]->(n)",
                    Map.of("ownerId", ownerId.toString(), "nodeId", nodeId.toString())));
        }
        for (N from : graph.nodes()) {
            for (N to : graph.neighbors(from)) {
                for (E edge : graph.edges(from, to)) {
                    saveGraphEdge(tx, from, to, edgeType, edge);
                }
            }
        }
    }

    /** MERGEs on the edge's own reflected property values as part of the match pattern itself, not via a
     *  bare-pattern MERGE followed by SET (contrast {@code TaggingBackendNeo4j}'s deliberate "zero or one
     *  association" bare-pattern MERGE) -- this is what gives {@code Set}-like value-based dedup, matching
     *  {@code KnowledgeGraph.edges(from, to)}'s {@code Set<E>} contract: two {@code addEdge} calls with
     *  identical edge property values collapse into one relationship, two calls with different values create
     *  two distinct relationships. */
    private void saveGraphEdge(SimpleQueryRunner tx, Object from, Object to, String edgeType, Object edge) {
        UUID fromId = EntityReflection.readId(from);
        UUID toId = EntityReflection.readId(to);
        String fromLabel = label(from.getClass());
        String toLabel = label(to.getClass());
        Map<String, Object> properties = edgeProperties(edge);
        Map<String, Object> params = new HashMap<>();
        params.put("fromId", fromId.toString());
        params.put("toId", toId.toString());
        StringBuilder pattern = new StringBuilder();
        int i = 0;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            pattern.append(i == 0 ? " {" : ", ").append('`').append(entry.getKey()).append("`: $p").append(i);
            params.put("p" + i, entry.getValue());
            i++;
        }
        if (i > 0) {
            pattern.append('}');
        }
        tx.run(new Query("MATCH (a:`" + fromLabel + "` {id: $fromId}), (b:`" + toLabel + "` {id: $toId}) "
                        + "MERGE (a)-[:`" + edgeType + "`" + pattern + "]->(b)", params));
    }

    /** An edge's simple-valued fields (or record components), converted for storage as relationship
     *  properties -- records (the idiomatic edge shape, e.g. {@code record RelatesTo(String reason)}) can't
     *  be reflectively field-read the way a plain class can, since their state lives behind synthesized
     *  accessor methods rather than directly reflectable fields in the same shape as an ordinary entity. */
    private static Map<String, Object> edgeProperties(Object edge) {
        Map<String, Object> properties = new LinkedHashMap<>();
        Class<?> edgeType = edge.getClass();
        if (edgeType.isRecord()) {
            for (RecordComponent component : edgeType.getRecordComponents()) {
                try {
                    Object value = component.getAccessor().invoke(edge);
                    if (isSimpleValue(value)) {
                        properties.put(component.getName(), toNeo4jValue(value));
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Cannot read record component " + component + " on " + edgeType, e);
                }
            }
        } else {
            for (Field field : EntityReflection.allFields(edgeType)) {
                Object value = EntityReflection.readField(edge, field.getName());
                if (isSimpleValue(value)) {
                    properties.put(field.getName(), toNeo4jValue(value));
                }
            }
        }
        return properties;
    }

    // ---- read: reflective hydration back into a plain Java object graph ------------------------

    /** {@code hydrated} caches by id within a single call so a cyclic relationship graph terminates. */
    private Object hydrate(Session session, Class<?> entityType, Node node, Map<UUID, Object> hydrated) {
        UUID id = UUID.fromString(node.get("id").asString());
        Object cached = hydrated.get(id);
        if (cached != null) {
            return cached;
        }
        Object entity;
        try {
            entity = entityType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(entityType + " needs a no-arg constructor to be hydrated from Neo4j", e);
        }
        EntityReflection.writeId(entity, id);
        hydrated.put(id, entity);

        for (Field field : EntityReflection.allFields(entityType)) {
            String fieldName = field.getName();
            if (isIdField(field) || isRelationshipField(field) || !node.containsKey(fieldName)) {
                continue;
            }
            setFieldFromNeo4jValue(entity, field, node.get(fieldName));
        }

        for (Field field : EntityReflection.allFields(entityType)) {
            if (!isRelationshipField(field)) {
                continue;
            }
            if (KnowledgeGraph.class.isAssignableFrom(field.getType())) {
                hydrateKnowledgeGraphField(session, entity, field.getName(), hydrated);
            } else {
                hydrateRelationshipField(session, entity, field.getName(), hydrated);
            }
        }
        hydrateVectors(entityType, entity, node);
        return entity;
    }

    /**
     * Serves each {@code @Vectorize} field's already-stored vector straight into the materialized
     * instance's cache slots, so reading it costs nothing instead of a fresh model call (OMI-187).
     *
     * <p>Cheaper here than on any other backend: JavAI's vectors are ordinary node properties, so they
     * arrived with the node this method is already reading. There is no extra query -- an entity loaded
     * from Neo4j has literally always been carrying its vectors, and until now threw them away and
     * re-embedded.
     *
     * <p>Only the currently-configured model's properties are read (they are qualified per model, exactly
     * so that vectors from different models can coexist), and {@code JavAIRuntime.hydrateFieldVector}
     * declines any slot a setter has already touched, so a genuine mutation still wins.
     */
    /** Marks a vector property (and its timestamp) for removal under the current model -- {@code null} in a
     *  {@code SET n += $props} map deletes the property rather than storing a null. */
    private static void clearVectorProperty(Map<String, Object> properties, String baseName, String modelId) {
        if (modelId == null) {
            return; // no model named, so no property name to target -- see JavAIEmbeddingProvider.modelId()
        }
        String qualified = qualify(baseName, modelId);
        properties.put(qualified, null);
        properties.put(qualified + "ComputedAt", null);
    }

    private void hydrateVectors(Class<?> entityType, Object entity, Node node) {
        if (!(entity instanceof JavAIVectorizable)) {
            return;
        }
        // Read before the currentModelId() guard below, deliberately: an @ExternalVector's model is declared
        // on the type, so it is readable whether or not a text provider is configured or can name itself
        // (OMI-290).
        hydrateExternalVectors(entityType, entity, node);
        String modelId = JavAIRuntime.currentModelId();
        if (modelId == null) {
            return;
        }
        for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
            String qualified = qualify(fieldName + "Vector", modelId);
            if (!node.containsKey(qualified)) {
                continue;
            }
            List<Object> raw = node.get(qualified).asList();
            float[] values = new float[raw.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ((Number) raw.get(i)).floatValue();
            }
            Instant computedAt = node.containsKey(qualified + "ComputedAt")
                    ? Instant.parse(node.get(qualified + "ComputedAt").asString())
                    : Instant.now();
            JavAIRuntime.hydrateFieldVector(entity, fieldName,
                    new EmbeddingVector(values, modelId, values.length, computedAt));
        }

        // The concatenated text vector is a real embedding, not arithmetic over field vectors, so skipping
        // this would mean a live model call on every load of every participating entity (OMI-191).
        String qualifiedConcat = qualify("concatenatedTextVector", modelId);
        if (JavAIRuntime.participatesInConcatenation(entityType) && node.containsKey(qualifiedConcat)
                && !node.get(qualifiedConcat).isNull()) {
            List<Object> raw = node.get(qualifiedConcat).asList();
            float[] values = new float[raw.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ((Number) raw.get(i)).floatValue();
            }
            Instant computedAt = node.containsKey(qualifiedConcat + "ComputedAt")
                    ? Instant.parse(node.get(qualifiedConcat + "ComputedAt").asString())
                    : Instant.now();
            JavAIRuntime.hydrateConcatenatedTextVector(entity,
                    new EmbeddingVector(values, modelId, values.length, computedAt));
        }
    }

    /**
     * Writes one {@code @ExternalVector}'s node properties and nothing else -- the narrow write behind
     * {@code supplyVector} (OMI-290). Not a {@code save()}: the consumer storing a vector has not touched
     * the entity itself.
     */
    @Override
    public void writeExternalVector(Class<?> entityType, Object entity, String vectorName) {
        UUID id = EntityReflection.readId(entity);
        String label = label(entityType);
        EmbeddingVector vector = ((JavAIVectorizable) entity).externalVector(vectorName);
        String declaredModel = JavAIRuntime.externalVectorModel(entityType, vectorName);
        Map<String, Object> properties = new HashMap<>();
        if (vector.isAbsent()) {
            clearVectorProperty(properties, vectorName + "Vector", declaredModel);
            properties.put(qualify(vectorName + "Vector", declaredModel) + "ComputedFor", null);
        } else {
            String qualified = qualify(vectorName + "Vector", vector.modelId());
            properties.put(qualified, vector.values());
            properties.put(qualified + "ComputedAt", vector.computedAt().toString());
            properties.put(qualified + "ComputedFor", JavAIRuntime.externalVectorKey(entity, vectorName));
        }
        try (Session session = driver().session()) {
            session.executeWrite(tx -> tx.run("MERGE (n:`" + label + "` {id: $id}) SET n += $props",
                    Values.parameters("id", id.toString(), "props", properties)).consume());
        }
    }

    /**
     * Restores each {@code @ExternalVector} from its declared model's node properties, together with the
     * content key it was written for (OMI-290).
     *
     * <p>The key is what makes the restored vector answerable at all: {@code externalVector()} compares it
     * against the entity's current content on every read, so hydrating the vector alone would leave the
     * entity holding something it can never serve -- indistinguishable, from outside, from a vector that had
     * been superseded, and from a pipeline that had never run.
     */
    private void hydrateExternalVectors(Class<?> entityType, Object entity, Node node) {
        for (String vectorName : JavAIRuntime.externalVectorNames(entityType)) {
            String declaredModel = JavAIRuntime.externalVectorModel(entityType, vectorName);
            String qualified = qualify(vectorName + "Vector", declaredModel);
            if (!node.containsKey(qualified) || node.get(qualified).isNull()) {
                continue;
            }
            List<Object> raw = node.get(qualified).asList();
            float[] values = new float[raw.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ((Number) raw.get(i)).floatValue();
            }
            Instant computedAt = node.containsKey(qualified + "ComputedAt")
                    ? Instant.parse(node.get(qualified + "ComputedAt").asString())
                    : Instant.now();
            String computedFor = node.containsKey(qualified + "ComputedFor")
                    && !node.get(qualified + "ComputedFor").isNull()
                    ? node.get(qualified + "ComputedFor").asString()
                    : null;
            JavAIRuntime.hydrateExternalVector(entity, vectorName,
                    new EmbeddingVector(values, declaredModel, values.length, computedAt), computedFor);
        }
    }

    /** One related node reached via a relationship, plus that relationship's {@code mapKey} property
     *  ({@code null} unless the owning field is a {@code Map}) -- see {@link #saveRelationship}. */
    private record RelatedNode(Node node, String mapKey) {
    }

    private void hydrateRelationshipField(Session session, Object owner, String fieldName, Map<UUID, Object> hydrated) {
        Field field = EntityReflection.findField(owner.getClass(), fieldName);
        UUID ownerId = EntityReflection.readId(owner);
        String ownerLabel = label(owner.getClass());
        String relationshipType = relationshipType(fieldName);

        List<RelatedNode> relatedNodes = session.executeRead(tx -> {
            var result = tx.run("MATCH (a:`" + ownerLabel + "` {id: $id})-[r:`" + relationshipType + "`]->(b) "
                    + "RETURN b, r.mapKey AS mapKey", Values.parameters("id", ownerId.toString()));
            List<RelatedNode> found = new ArrayList<>();
            for (Record record : result.list()) {
                Value mapKeyValue = record.get("mapKey");
                found.add(new RelatedNode(record.get("b").asNode(), mapKeyValue.isNull() ? null : mapKeyValue.asString()));
            }
            return found;
        });
        if (relatedNodes.isEmpty()) {
            return;
        }

        Object currentValue = EntityReflection.readField(owner, fieldName);
        if (currentValue instanceof Map<?, ?> existingMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) existingMap;
            for (RelatedNode related : relatedNodes) {
                map.put(related.mapKey(), hydrateRelated(session, related.node(), hydrated));
            }
        } else if (currentValue instanceof Collection<?> existingCollection) {
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) existingCollection;
            for (RelatedNode related : relatedNodes) {
                collection.add(hydrateRelated(session, related.node(), hydrated));
            }
        } else {
            Object related = hydrateRelated(session, relatedNodes.get(0).node(), hydrated);
            try {
                field.setAccessible(true);
                field.set(owner, related);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot write field " + field + " on " + owner.getClass(), e);
            }
        }
    }

    private Object hydrateRelated(Session session, Node node, Map<UUID, Object> hydrated) {
        UUID id = UUID.fromString(node.get("id").asString());
        Object cached = hydrated.get(id);
        if (cached != null) {
            return cached;
        }
        String labelName = node.labels().iterator().next();
        Class<?> relatedType = typesByLabel.get(labelName);
        if (relatedType == null) {
            throw new IllegalStateException("No entity type registered for node label '" + labelName + "' -- "
                    + "call JavAIPI.repository(...) for that entity's own repository interface before "
                    + "traversing a relationship that reaches it (see registerEntityType's javadoc)");
        }
        return hydrate(session, relatedType, node, hydrated);
    }

    /** Mirrors {@link #saveKnowledgeGraphField}'s two field-name-scoped relationship types: queries {@code
     *  <FIELD>_MEMBER} to rebuild node membership (including isolated nodes with no edges), then a single
     *  query for {@code <FIELD>_EDGE} relationships explicitly scoped to both endpoints being members of
     *  *this* owner's graph -- not "all edges of this type anywhere" -- so two different owners' KnowledgeGraph
     *  fields sharing the same node/edge types never cross-contaminate. Rebuilds a fresh, plain, in-memory
     *  {@code JavAIKnowledgeGraph} and writes it onto the field; there is no live/reactive persisted graph. */
    private void hydrateKnowledgeGraphField(Session session, Object owner, String fieldName, Map<UUID, Object> hydrated) {
        Field field = EntityReflection.findField(owner.getClass(), fieldName);
        UUID ownerId = EntityReflection.readId(owner);
        String ownerLabel = label(owner.getClass());
        String memberType = relationshipType(fieldName) + "_MEMBER";
        String edgeType = relationshipType(fieldName) + "_EDGE";
        Class<?> edgeClass = genericTypeArgument(field, 1);
        if (edgeClass == null) {
            throw new IllegalStateException("Cannot resolve KnowledgeGraph edge type for " + field
                    + " -- declare it as a concrete KnowledgeGraph<NodeType, EdgeType> field");
        }
        JavAIKnowledgeGraph<JavAIGraphNode, JavAIEdge> graph = new JavAIKnowledgeGraph<>();

        List<Node> memberNodes = session.executeRead(tx -> {
            var result = tx.run(new Query("MATCH (owner:`" + ownerLabel + "` {id: $ownerId})-[:`" + memberType + "`]->(n) RETURN n",
                    Map.of("ownerId", ownerId.toString())));
            List<Node> found = new ArrayList<>();
            for (Record record : result.list()) {
                found.add(record.get("n").asNode());
            }
            return found;
        });
        for (Node node : memberNodes) {
            graph.addNode((JavAIGraphNode) hydrateRelated(session, node, hydrated));
        }

        record EdgeTriple(Node from, Relationship edge, Node to) {
        }
        List<EdgeTriple> edgeTriples = session.executeRead(tx -> {
            var result = tx.run(new Query(
                    "MATCH (owner:`" + ownerLabel + "` {id: $ownerId})-[:`" + memberType + "`]->(a)"
                            + "-[e:`" + edgeType + "`]->(b)<-[:`" + memberType + "`]-(owner) RETURN a, e, b",
                    Map.of("ownerId", ownerId.toString())));
            List<EdgeTriple> found = new ArrayList<>();
            for (Record record : result.list()) {
                found.add(new EdgeTriple(record.get("a").asNode(), record.get("e").asRelationship(), record.get("b").asNode()));
            }
            return found;
        });
        for (EdgeTriple triple : edgeTriples) {
            JavAIGraphNode from = (JavAIGraphNode) hydrateRelated(session, triple.from(), hydrated);
            JavAIGraphNode to = (JavAIGraphNode) hydrateRelated(session, triple.to(), hydrated);
            JavAIEdge edge = (JavAIEdge) hydrateEdge(edgeClass, triple.edge());
            graph.addEdge(from, to, edge);
        }

        try {
            field.setAccessible(true);
            field.set(owner, graph);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write field " + field + " on " + owner.getClass(), e);
        }
    }

    /** Reconstructs an edge instance from a relationship's stored properties. Records (the idiomatic edge
     *  shape) can't be field-assigned after construction, so their canonical constructor is invoked directly
     *  with each {@code RecordComponent}'s converted value; plain classes fall back to the same no-arg-
     *  constructor + reflective-field-write pattern already used for ordinary entity hydration. */
    private static Object hydrateEdge(Class<?> edgeType, Relationship relationship) {
        if (edgeType.isRecord()) {
            RecordComponent[] components = edgeType.getRecordComponents();
            Class<?>[] paramTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                paramTypes[i] = components[i].getType();
                args[i] = relationship.containsKey(components[i].getName())
                        ? convertNeo4jValue(relationship.get(components[i].getName()), paramTypes[i])
                        : null;
            }
            try {
                return edgeType.getDeclaredConstructor(paramTypes).newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot reconstruct record " + edgeType + " from relationship properties", e);
            }
        }
        Object edge;
        try {
            edge = edgeType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(edgeType + " needs a no-arg constructor, or must be a record, to be "
                    + "hydrated as a KnowledgeGraph edge from Neo4j", e);
        }
        for (Field field : EntityReflection.allFields(edgeType)) {
            if (relationship.containsKey(field.getName())) {
                setFieldFromNeo4jValue(edge, field, relationship.get(field.getName()));
            }
        }
        return edge;
    }

    // ---- field <-> Neo4j value conversion --------------------------------------------------------

    private static boolean isIdField(Field field) {
        return field.isAnnotationPresent(jakarta.persistence.Id.class);
    }

    /** By declared type, not the runtime value -- so a currently-null singular reference (e.g. an unset
     *  {@code Article.draftComment}) is still correctly routed to the relationship pass, not silently
     *  treated as a plain (null-valued) property. See the class javadoc for why this is declared-type-
     *  driven rather than keyed off {@code @Summary}. */
    private static boolean isRelationshipField(Field field) {
        Class<?> type = field.getType();
        return Map.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type)
                || KnowledgeGraph.class.isAssignableFrom(type) || JavAIVectorizable.class.isAssignableFrom(type);
    }

    private static boolean isSimpleValue(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof UUID || value instanceof Enum<?> || value instanceof Instant;
    }

    private static Object toNeo4jValue(Object value) {
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

    private static void setFieldFromNeo4jValue(Object entity, Field field, Value value) {
        try {
            field.setAccessible(true);
            field.set(entity, convertNeo4jValue(value, field.getType()));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write field " + field + " on " + entity.getClass(), e);
        }
    }

    /** Shared by {@link #setFieldFromNeo4jValue} (field assignment) and {@link #hydrateEdge}'s record path
     *  (constructor arguments) -- a record's canonical constructor needs converted VALUES, not a field-
     *  assignment side effect, so the conversion logic lives here rather than only inside a setter. */
    private static Object convertNeo4jValue(Value value, Class<?> targetType) {
        if (Point.class.isAssignableFrom(targetType)) {
            org.neo4j.driver.types.Point point = value.asPoint();
            return new Point(point.x(), point.y()); // x = longitude, y = latitude (WGS-84)
        }
        if (targetType == String.class) {
            return value.asString();
        } else if (targetType == UUID.class) {
            return UUID.fromString(value.asString());
        } else if (targetType == Instant.class) {
            return Instant.parse(value.asString());
        } else if (targetType.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumValue = Enum.valueOf((Class<Enum>) targetType, value.asString());
            return enumValue;
        } else if (targetType == int.class || targetType == Integer.class) {
            return value.asInt();
        } else if (targetType == long.class || targetType == Long.class) {
            return value.asLong();
        } else if (targetType == double.class || targetType == Double.class) {
            return value.asDouble();
        } else if (targetType == float.class || targetType == Float.class) {
            return (float) value.asDouble();
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            return value.asBoolean();
        } else {
            return value.asObject();
        }
    }

    private static String relationshipType(String fieldName) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(c));
        }
        return result.toString();
    }

    /**
     * The {@code @Taggregate} containment of the registered model, as relationship traversal (OMI-304).
     *
     * <p>The <em>declaration</em> comes from {@link Containment} exactly as it does on Postgres -- which
     * field of which type holds what is reflection over the model and has nothing to do with a store. Only
     * the traversal is native here: this backend maps a collection or reference field to a relationship
     * named after it, so "which containers hold this member" is the same edge read backwards.
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
                Class<?> childType = typeOf(childTypeName);
                if (childType == null) {
                    return;
                }
                try (Session session = driver().session()) {
                    for (Containment.Edge edge : containment().taggregateEdges()) {
                        if (!edge.childType().isAssignableFrom(childType)) {
                            continue;
                        }
                        var result = session.run("MATCH (p:`" + label(edge.parentType()) + "`)-[:`"
                                        + relationshipType(edge.fieldName()) + "`]->(c:`" + label(childType)
                                        + "` {id: $childId}) RETURN p.id AS id",
                                Values.parameters("childId", childId.toString()));
                        for (Record record : result.list()) {
                            sink.accept(edge.parentType().getName(), UUID.fromString(record.get("id").asString()));
                        }
                    }
                }
            }

            @Override
            public void membersOf(String containerTypeName, UUID containerId, BiConsumer<String, UUID> sink) {
                Class<?> containerType = typeOf(containerTypeName);
                if (containerType == null) {
                    return;
                }
                try (Session session = driver().session()) {
                    for (Containment.Edge edge : containment().taggregateEdges()) {
                        if (!edge.parentType().isAssignableFrom(containerType)) {
                            continue;
                        }
                        var result = session.run("MATCH (p:`" + label(containerType) + "` {id: $containerId})-[:`"
                                        + relationshipType(edge.fieldName()) + "`]->(c:`"
                                        + label(edge.childType()) + "`) RETURN c.id AS id",
                                Values.parameters("containerId", containerId.toString()));
                        for (Record record : result.list()) {
                            sink.accept(edge.childType().getName(), UUID.fromString(record.get("id").asString()));
                        }
                    }
                }
            }

            @Override
            public void allContainers(BiConsumer<String, UUID> sink) {
                try (Session session = driver().session()) {
                    for (Class<?> containerType : containment().taggregateContainerTypes()) {
                        var result = session.run(
                                "MATCH (p:`" + label(containerType) + "`) RETURN p.id AS id");
                        for (Record record : result.list()) {
                            sink.accept(containerType.getName(), UUID.fromString(record.get("id").asString()));
                        }
                    }
                }
            }

            /** No ambient JDBC transaction exists on this backend -- tagging writes on its own connection,
             *  and says so by getting {@code false} rather than a silently-ignored callback. */
            @Override
            public boolean inAmbientTransaction(ConnectionWork work) {
                return false;
            }

            /** No ambient transaction to hang a commit callback on either -- the caller drains inline. */
            @Override
            public boolean afterCommit(Runnable drain) {
                return false;
            }
        };
    }

    /**
     * The declared containment of every registered type, resolved once on first use.
     *
     * <p>Lazily rather than in the constructor for the same reason the Postgres backend does it: registration
     * is still in progress there, and by the first containment question every repository this application
     * needs has necessarily been created.
     */
    private Containment containment() {
        Containment resolved = containment;
        if (resolved == null) {
            synchronized (this) {
                resolved = containment;
                if (resolved == null) {
                    resolved = Containment.of(typesByLabel.values());
                    containment = resolved;
                }
            }
        }
        return resolved;
    }

    private Class<?> typeOf(String typeName) {
        for (Class<?> registered : typesByLabel.values()) {
            if (registered.getName().equals(typeName)) {
                return registered;
            }
        }
        return null;
    }

    private static String label(Class<?> entityType) {
        return entityType.getSimpleName();
    }

    // ---- lazy bootstrap -----------------------------------------------------------------------

    private Driver driver() {
        Driver current = driver;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (driver == null) {
                driver = config.externalNeo4jDriver() != null
                        ? config.externalNeo4jDriver()
                        : GraphDatabase.driver(config.neo4jUri(), AuthTokens.basic(config.neo4jUsername(), config.neo4jPassword()));
            }
            return driver;
        }
    }
}
