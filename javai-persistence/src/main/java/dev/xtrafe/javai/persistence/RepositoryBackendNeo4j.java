package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.collections.JavAIEdge;
import dev.xtrafe.javai.collections.JavAIGraphNode;
import dev.xtrafe.javai.collections.JavAIKnowledgeGraph;
import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
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

    private final JavAIPersistenceConfig config;
    private final Set<String> vectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final Map<String, Class<?>> typesByLabel = new ConcurrentHashMap<>();
    private volatile Driver driver;

    RepositoryBackendNeo4j(JavAIPersistenceConfig config) {
        this.config = config;
    }

    @Override
    public void registerEntityType(Class<?> entityType) {
        // Neo4j has no boot-time metadata to accumulate the way Hibernate does, but a label->Class
        // registry is still needed to hydrate a related node reached only via a @Summary relationship
        // traversal, where the target's Java type isn't otherwise known. Same "register everything you
        // need before using it" rule as the Postgres backend: a related entity type has to have its own
        // repository() call made at some point before traversal-hydration needs to resolve its label.
        validateMapKeyTypesAreSupported(entityType);
        typesByLabel.put(label(entityType), entityType);
    }

    /** Fails fast, at registration time, for a {@code Map} relationship field keyed by anything other than
     *  {@code String} -- the relationship's {@code mapKey} property is a plain string (see
     *  {@link #saveRelationship}), so a stringified non-{@code String} key could never correctly round-trip
     *  back to its original type on hydration. Mirrors {@code RepositoryBackendHibernatePostgres}'s own
     *  identical limitation/validation for the same reason. */
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
        String label = label(entityType);
        String property = qualify(basePropertyName, reference.modelId());
        ensureVectorIndex(label, property, reference.dims());
        String indexName = vectorIndexName(label, property);
        try (Session session = driver().session()) {
            List<Node> nodes = session.executeRead(tx -> {
                var result = tx.run("CALL db.index.vector.queryNodes($indexName, $limit, $reference) YIELD node RETURN node",
                        Values.parameters("indexName", indexName, "limit", limit, "reference", reference.values()));
                List<Node> found = new ArrayList<>();
                for (Record record : result.list()) {
                    found.add(record.get("node").asNode());
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

    private void ensureVectorIndex(String label, String property, int dims) {
        String key = label + "." + property;
        if (!vectorIndexesEnsured.add(key)) {
            return;
        }
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE VECTOR INDEX `" + vectorIndexName(label, property) + "` IF NOT EXISTS "
                        + "FOR (n:`" + label + "`) ON n.`" + property + "` "
                        + "OPTIONS {indexConfig: {`vector.dimensions`: $dims, `vector.similarity_function`: 'cosine'}}",
                        Values.parameters("dims", dims));
                return null;
            });
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
            for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
                EmbeddingVector vector = vectorizable.fieldVector(fieldName);
                String qualified = qualify(fieldName + "Vector", vector.modelId());
                properties.put(qualified, vector.values());
                properties.put(qualified + "ComputedAt", vector.computedAt().toString());
            }
            EmbeddingVector combined = vectorizable.vector();
            String qualifiedCombined = qualify("vector", combined.modelId());
            properties.put(qualifiedCombined, combined.values());
            properties.put(qualifiedCombined + "ComputedAt", combined.computedAt().toString());

            EmbeddingVector summary = vectorizable.summaryVector();
            String qualifiedSummary = qualify("summaryVector", summary.modelId());
            properties.put(qualifiedSummary, summary.values());
            properties.put(qualifiedSummary + "ComputedAt", summary.computedAt().toString());
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
        return entity;
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
