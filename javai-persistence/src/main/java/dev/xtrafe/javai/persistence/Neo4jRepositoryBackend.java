package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIVectorizable;
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

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
 * sum" (an in-memory, {@code javai-runtime} concern) and has nothing to do with whether a field should
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
 * {@code HibernatePostgresRepositoryBackend} uses for its per-model tables, and for the same reason: two
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
final class Neo4jRepositoryBackend implements RepositoryBackend {

    private final JavAIPersistenceConfig config;
    private final Set<String> vectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final Map<String, Class<?>> typesByLabel = new ConcurrentHashMap<>();
    private volatile Driver driver;

    Neo4jRepositoryBackend(JavAIPersistenceConfig config) {
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
     *  back to its original type on hydration. Mirrors {@code HibernatePostgresRepositoryBackend}'s own
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
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                saveNode(tx, entity, new IdentityHashMap<>());
                return null;
            });
        }
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
        JavAIVectorizable vectorizable = (JavAIVectorizable) entity;
        Set<String> vectorizeFields = EntityReflection.vectorizeFieldNames(entityType);

        Map<String, Object> properties = new HashMap<>();
        for (Field field : EntityReflection.allFields(entityType)) {
            String fieldName = field.getName();
            if (isIdField(field) || isRelationshipField(field)) {
                continue; // id is the MERGE key, handled separately; relationships handled in the pass below
            }
            Object value = EntityReflection.readField(entity, fieldName);
            if (isSimpleValue(value)) {
                properties.put(fieldName, toNeo4jValue(value));
            }
            // else: not graph-shaped but also not a simple type -- documented Phase 0 boundary, skipped.
        }
        for (String fieldName : vectorizeFields) {
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

        tx.run("MERGE (n:`" + label + "` {id: $id}) SET n += $props",
                Values.parameters("id", id.toString(), "props", properties));

        for (Field field : EntityReflection.allFields(entityType)) {
            if (!isRelationshipField(field)) {
                continue;
            }
            String fieldName = field.getName();
            Object value = EntityReflection.readField(entity, fieldName);
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
            if (isRelationshipField(field)) {
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
                || JavAIVectorizable.class.isAssignableFrom(type);
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
            Class<?> type = field.getType();
            if (type == String.class) {
                field.set(entity, value.asString());
            } else if (type == UUID.class) {
                field.set(entity, UUID.fromString(value.asString()));
            } else if (type == Instant.class) {
                field.set(entity, Instant.parse(value.asString()));
            } else if (type.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object enumValue = Enum.valueOf((Class<Enum>) type, value.asString());
                field.set(entity, enumValue);
            } else if (type == int.class || type == Integer.class) {
                field.set(entity, value.asInt());
            } else if (type == long.class || type == Long.class) {
                field.set(entity, value.asLong());
            } else if (type == double.class || type == Double.class) {
                field.set(entity, value.asDouble());
            } else if (type == float.class || type == Float.class) {
                field.set(entity, (float) value.asDouble());
            } else if (type == boolean.class || type == Boolean.class) {
                field.set(entity, value.asBoolean());
            } else {
                field.set(entity, value.asObject());
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write field " + field + " on " + entity.getClass(), e);
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
