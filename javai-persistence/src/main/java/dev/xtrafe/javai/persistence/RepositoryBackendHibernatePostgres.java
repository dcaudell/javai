package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import jakarta.persistence.Entity;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaRoot;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Point;
import org.springframework.data.repository.query.parser.Part;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Postgres/pgvector backend. Owns one lazily-built, shared internal {@link SessionFactory} (unless the
 * caller supplied one via {@link JavAIPersistenceConfig.Builder#sessionFactory}), covering every entity
 * type registered via {@link #registerEntityType} up to the moment the *first* repository method is
 * actually invoked -- Hibernate's boot-time metadata is immutable once the {@code SessionFactory} is built,
 * so entity types registered afterward would silently never be mapped. See {@link JavAIPI}'s javadoc for
 * the "register every repository before using any of them" usage rule this implies.
 *
 * <p><b>One table per model, not one shared table.</b> Vectors live in tables this backend owns and names
 * itself -- {@code javai_vectors__<model>} (per-field, plus the object's own combined vector under the
 * {@link RepositoryBackend#COMBINED_VECTOR_FIELD} sentinel) and {@code javai_summary_vectors__<model>}
 * ({@code summaryVector()}, one row per object) -- where {@code <model>} is {@link ModelIds#sanitize}
 * applied to {@code EmbeddingVector.modelId()}. This is the key design point, and the reason it replaced an
 * earlier single-shared-table design: pgvector's HNSW index requires a *fixed*-dimension column, which
 * ruled out one column serving multiple models with different output widths; but even for two models that
 * happen to share a dimension, comparing their vectors against each other is semantically meaningless, so
 * physical separation is the correct model regardless of dimension. A concrete, useful consequence:
 * swapping the configured provider is *exactly* the same operation whether or not the new model's output
 * dimension differs from the old one -- {@code javai.persistence.*} config points at a different model, and
 * the next {@code save()} creates that model's own, correctly-dimensioned table the first time it's
 * needed, no special-casing. The developer's own {@code @Entity} table is never touched by this backend
 * beyond ordinary Hibernate persistence of the entity itself.
 *
 * <p><b>Reindexing and reverting.</b> {@link JavAIRepository#reindexAll()} is the explicit trigger for
 * "I swapped providers, now go re-embed everything": it re-saves every existing entity, which -- since
 * {@code save()} always writes under whichever provider is *currently* configured -- populates the new
 * model's tables while leaving every older model's tables completely untouched. Reverting to a previously-
 * used provider therefore needs no reindexing at all: {@code findNearestBy*} resolves its table from the
 * reference vector's own {@code modelId()}, so switching the configured provider back is immediately
 * correct against whatever that model's table already holds.
 *
 * <p><b>Singular vs. collection-typed relational fields.</b> A field holding a single related entity
 * (e.g. a {@code @OneToOne}) is ordinary Hibernate mapping -- no conflict, since Hibernate never needs to
 * substitute anything for a non-collection field. A {@code Collection}/{@code Map}-typed field is
 * different: Hibernate always substitutes its own {@code PersistentBag}/{@code PersistentSet}/
 * {@code PersistentMap} the instant the field is persisted, which fails outright (a
 * {@code ClassCastException}, confirmed empirically) if the field is statically typed as a concrete JavAI
 * collection class ({@code JavAIArrayList}/{@code JavAILinkedHashSet}/{@code JavAILinkedHashMap}) rather
 * than a plain {@code List}/{@code Set}/{@code Map} interface -- and even declaring the field by interface
 * type wouldn't help, since Hibernate would then silently replace the real instance with its own wrapper,
 * permanently discarding the JavAI object's vector/dirty-tracking behavior with no error at all. Rather than
 * force every JavAI collection field to give up its real type (or its behavior) for Hibernate's sake, such
 * fields are excluded from Hibernate's mapping entirely and instead round-trip through
 * {@code javai_collection_members} -- a single, shared (not per-model) table this backend owns
 * (owner/field/member identity, an optional string key for {@code Map} fields, and an ordinal for order),
 * populated by {@link #syncCollectionMembers} on save and read back by {@link #hydrateCollectionMembers} on
 * load. Being reflective rather than proxy-based, hydration adds members into whatever collection instance
 * the entity's own no-arg constructor already created (a real {@code JavAIArrayList}, full dirty-tracking
 * intact) instead of replacing it -- the same trick {@code RepositoryBackendNeo4j} already relies on for
 * its own relationship hydration, applied here for symmetry across both backends.
 *
 * <p><b>No manual {@code @Transient} required.</b> {@link #registerEntityType} reflectively detects, for
 * every registered entity type, which fields are shaped like a JavAI collection (a {@code Collection}/
 * {@code Map} that also implements {@link JavAIDirtyTracking} -- true of {@code JavAIArrayList}/
 * {@code JavAILinkedHashSet}/{@code JavAILinkedHashMap} today, and of any future JavAI collection type
 * following the same pattern, with no code change needed here) and, at {@link #buildSessionFactory}
 * time, generates an in-memory JPA {@code orm.xml}-equivalent mapping document that marks exactly those
 * fields {@code <transient>} -- fed to Hibernate via {@link MetadataSources#addInputStream}, alongside the
 * ordinary {@code @Entity}-driven annotation scanning. This is a real, spec-defined JPA override mechanism
 * (XML mappings logically override annotations for whatever they explicitly mention, leaving everything
 * else annotation-driven), not a hack -- the developer's source needs no {@code @Transient} on these
 * fields at all; detection is 100%-confidence from the field's declared type alone, since a
 * {@code JavAIDirtyTracking}-implementing collection can never be validly Hibernate-mapped natively
 * regardless of context.
 *
 * <p><b>Related entity types are auto-registered too.</b> {@link #registerEntityType} also walks each
 * registered type's fields recursively: a singular {@code @Entity}-typed field, or a {@code Collection}/
 * {@code Map} field whose element/value type is {@code @Entity}-annotated, is registered automatically.
 * A developer who only ever calls {@code JavAIPI.repository(ArticleRepository.class)} no longer needs to
 * separately realize {@code CommentRepository}/{@code AttachmentRepository} first just to get those types
 * into Hibernate's boot metadata -- reachability through {@code Article}'s own fields is enough.
 *
 * <p><b>Known limitation: {@code Map} fields must be keyed by {@code String} in this phase.</b> The
 * membership table's key column is a plain {@code varchar}; a {@code JavAILinkedHashMap<K, V>} field is
 * only supported when {@code K} is {@code String}. {@link #registerEntityType} validates this eagerly, at
 * registration time, and throws a clear {@code IllegalArgumentException} for any other key type rather than
 * silently storing a stringified key that could never correctly round-trip back to its original type.
 *
 * <p><b>Future direction.</b> A more ambitious fix -- letting these fields map <em>natively</em> via
 * Hibernate's {@code org.hibernate.usertype.UserCollectionType} SPI, which lets a custom
 * {@code PersistentCollection} implementation stand in for {@code PersistentBag}/{@code PersistentSet}/
 * {@code PersistentMap} and could in principle preserve JavAI's vector/dirty-tracking behavior *while*
 * being a real, natively Hibernate-managed association -- is deliberately not pursued here. It's
 * Postgres/Hibernate-only (Neo4j has no equivalent concept and would remain on its own reflective
 * mechanism regardless, breaking the symmetry the two backends otherwise share), and correctly
 * implementing a custom {@code PersistentCollection} is a substantial, easy-to-get-subtly-wrong undertaking
 * (dirty-checking snapshot semantics, session attachment/detachment, lazy-initialization) disproportionate
 * to a Phase 0 module whose job is proving the design space, not hardening a production ORM integration.
 * See {@code javai-persistence/README.md} for the same note in context.
 */
final class RepositoryBackendHibernatePostgres implements RepositoryBackend {

    private static final String FIELD_VECTOR_TABLE_PREFIX = "javai_vectors__";
    private static final String SUMMARY_VECTOR_TABLE_PREFIX = "javai_summary_vectors__";

    private final JavAIPersistenceConfig config;
    private final Set<Class<?>> registeredEntityTypes = ConcurrentHashMap.newKeySet();
    private final Object bootstrapLock = new Object();
    private volatile SessionFactory sessionFactory;

    RepositoryBackendHibernatePostgres(JavAIPersistenceConfig config) {
        this.config = config;
    }

    @Override
    public void registerEntityType(Class<?> entityType) {
        if (sessionFactory != null) {
            throw new IllegalStateException("Cannot register " + entityType.getName() + " -- the SessionFactory "
                    + "was already built from an earlier repository()'s first use. Call JavAIPI.repository(...) "
                    + "for every repository interface before invoking methods on any of them.");
        }
        registerEntityTypeRecursively(entityType, new HashSet<>());
    }

    /** Registers {@code entityType} and, recursively, every related entity type reachable through its own
     *  fields -- a singular {@code @Entity}-typed field, or the element/value type of a {@code Collection}/
     *  {@code Map} field, if that type is itself {@code @Entity}-annotated. See this class's own javadoc
     *  ("Related entity types are auto-registered too") for why: it removes the need to separately realize
     *  a repository for every related type just to get it into Hibernate's boot metadata. {@code visited}
     *  guards against infinite recursion through a cyclic object graph (e.g. two entities each referencing
     *  the other). */
    private void registerEntityTypeRecursively(Class<?> entityType, Set<Class<?>> visited) {
        if (!visited.add(entityType)) {
            return;
        }
        validateMapKeyTypesAreSupported(entityType);
        validateNoKnowledgeGraphFields(entityType);
        registeredEntityTypes.add(entityType);
        for (Field field : EntityReflection.allFields(entityType)) {
            Class<?> relatedType = relatedEntityType(field);
            if (relatedType != null) {
                registerEntityTypeRecursively(relatedType, visited);
            }
        }
    }

    /** The {@code @Entity}-annotated type reachable through {@code field}, if any: the field's own type for
     *  a singular reference, or its generic element type (collection) / value type (map) otherwise. Returns
     *  {@code null} for anything else (scalar fields, non-entity related types, unresolvable generics). */
    private static Class<?> relatedEntityType(Field field) {
        Class<?> fieldType = field.getType();
        if (fieldType.isAnnotationPresent(Entity.class)) {
            return fieldType;
        }
        if (Map.class.isAssignableFrom(fieldType)) {
            Class<?> valueType = genericTypeArgument(field, 1);
            return valueType != null && valueType.isAnnotationPresent(Entity.class) ? valueType : null;
        }
        if (Collection.class.isAssignableFrom(fieldType)) {
            Class<?> elementType = genericTypeArgument(field, 0);
            return elementType != null && elementType.isAnnotationPresent(Entity.class) ? elementType : null;
        }
        return null;
    }

    /** {@code field}'s {@code index}-th generic type argument as a raw {@code Class}, or {@code null} if
     *  the field isn't parameterized or that argument isn't a simple class (e.g. itself a wildcard/generic
     *  type variable). {@code List<Comment>} -> index 0 is {@code Comment}; {@code Map<String, Comment>} ->
     *  index 0 is {@code String}, index 1 is {@code Comment}. */
    private static Class<?> genericTypeArgument(Field field, int index) {
        if (field.getGenericType() instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (index < args.length && args[index] instanceof Class<?> clazz) {
                return clazz;
            }
        }
        return null;
    }

    /** A JavAI collection field is one that's both shaped like a {@code Collection}/{@code Map} AND
     *  implements {@link JavAIDirtyTracking} -- true of every concrete {@code javai-model} collection
     *  type ({@code JavAIArrayList}/{@code JavAILinkedHashSet}/{@code JavAILinkedHashMap}) today, and of any
     *  future one following the same pattern, with zero changes needed here. This check is 100%-confidence,
     *  not a heuristic: such a field can never be validly Hibernate-mapped natively (see this class's own
     *  javadoc), so auto-excluding it is always correct, never a loss of an alternative that could have
     *  worked. */
    private static boolean isJavAICollectionField(Field field) {
        Class<?> type = field.getType();
        boolean collectionShaped = Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
        return collectionShaped && JavAIDirtyTracking.class.isAssignableFrom(type);
    }

    /** A field this backend maps itself rather than letting Hibernate map it -- either a JavAI collection
     *  (round-tripped through {@code javai_collection_members}) or a geo {@code Point} (through
     *  {@code javai_geo_points} + earthdistance). Both are marked {@code <transient>} in the generated
     *  override mapping so Hibernate's own boot-time mapping doesn't choke on an unmappable field type. */
    private static boolean isBackendManagedField(Field field) {
        return isJavAICollectionField(field) || Point.class.isAssignableFrom(field.getType());
    }

    /** Fails fast, at registration time, for a JavAI {@code Map} field keyed by anything other than
     *  {@code String} -- see this class's own javadoc ("Known limitation") for why: silently storing a
     *  stringified key that can never correctly round-trip back to its original type would be a much worse
     *  outcome than a clear, immediate error. */
    private static void validateMapKeyTypesAreSupported(Class<?> entityType) {
        for (Field field : EntityReflection.allFields(entityType)) {
            if (!isJavAICollectionField(field) || !Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Class<?> keyType = genericTypeArgument(field, 0);
            if (keyType != String.class) {
                throw new IllegalArgumentException("Postgres persistence only supports String-keyed JavAI map "
                        + "fields in this phase -- " + entityType.getName() + "." + field.getName() + " is keyed "
                        + "by " + (keyType == null ? "an unresolvable type" : keyType.getName()));
            }
        }
    }

    /** Fails fast, at registration time, for a {@code KnowledgeGraph}-typed field -- it's neither
     *  {@code @Entity}-annotated (so {@link #relatedEntityType} never routes it to auto-registration) nor
     *  {@code Collection}/{@code Map}-shaped (so {@link #isJavAICollectionField} never routes it to this
     *  backend's own reflective membership table either); left unguarded, it would fall through to being
     *  handed to Hibernate as an ordinary field and fail with a confusing boot-time mapping exception
     *  instead. {@code KnowledgeGraph} persistence is Neo4j-only in this phase -- see
     *  {@code RepositoryBackendNeo4j}'s own {@code saveKnowledgeGraphField}/{@code hydrateKnowledgeGraphField}
     *  and doc/spec/persistence-bridge.md for why (native multi-hop traversal + hybrid similarity/structure
     *  querying has no efficient equivalent to build here in this phase). */
    private static void validateNoKnowledgeGraphFields(Class<?> entityType) {
        for (Field field : EntityReflection.allFields(entityType)) {
            if (KnowledgeGraph.class.isAssignableFrom(field.getType())) {
                throw new IllegalArgumentException("Postgres persistence does not support KnowledgeGraph fields -- "
                        + entityType.getName() + "." + field.getName() + " is a KnowledgeGraph. KnowledgeGraph "
                        + "persistence is Neo4j-only in this phase; use JavAIPersistenceConfig.Backend.NEO4J for "
                        + "any entity type that declares one.");
            }
        }
    }

    @Override
    public Object save(Class<?> entityType, Object entity) {
        // The whole reachable subgraph is locked (and every field/summary vector forced accurate) for the
        // duration of the flush below -- this is what guarantees writeVectors()/writeVectorsForRelatedEntities()
        // below can never persist a vector that's stale relative to the field value committed in this same
        // transaction, regardless of the ambient EmbeddingConsistencyMode.
        Object[] result = new Object[1];
        JavAIRuntime.runWithSubgraphLockedForPersistence(entity, () -> {
            SessionFactory factory = sessionFactory();
            try (Session session = factory.openSession()) {
                Transaction tx = session.beginTransaction();
                try {
                    // Assigned before merge(), recursively: a cascaded @OneToOne (or a @Transient collection
                    // element this backend persists itself) needs its own id set before Hibernate/this backend
                    // tries to INSERT it -- there's no @GeneratedValue, identity is always application-assigned.
                    ensureIdsAssigned(entity, new IdentityHashMap<>());
                    Object managed = session.merge(entity);
                    session.flush();
                    writeVectors(session, entityType, managed);
                    writeVectorsForRelatedEntities(session, managed);
                    // Reads from the original `entity`, not `managed`: the collection field is @Transient, so
                    // merge() never copies its contents onto the managed instance (transient state isn't part
                    // of what merge() reconciles) -- managed.comments would still be the empty list its own
                    // no-arg constructor produced. entity's id was already assigned above, so it matches.
                    syncCollectionMembers(session, entityType, entity);
                    // Same reason as syncCollectionMembers: a Point field is @Transient (mapped by this
                    // backend, not Hibernate), so its value lives only on the original `entity`, not `managed`.
                    syncGeoPoints(session, entity, new IdentityHashMap<>());
                    tx.commit();
                    // Returns the original `entity`, not `managed`: the same reason as above -- `managed`'s
                    // @Transient collection fields are left empty by merge(), so returning it would hand the
                    // caller back an Article whose in-memory `comments` looks wrong immediately after save().
                    // `entity` already carries every assigned id (ensureIdsAssigned mutates it in place) and is
                    // what RepositoryBackendNeo4j.save() returns too, so both backends behave consistently.
                    result[0] = entity;
                } catch (RuntimeException e) {
                    tx.rollback();
                    throw e;
                }
            }
        });
        return result[0];
    }

    @Override
    public Optional<Object> findById(Class<?> entityType, UUID id) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            Object entity = session.find(entityType, id);
            if (entity != null) {
                hydrateCollectionMembers(session, entity);
                hydrateGeoPoints(session, entity, new IdentityHashMap<>());
            }
            return Optional.ofNullable(entity);
        }
    }

    @Override
    public List<Object> findAll(Class<?> entityType) {
        return findAllTyped(entityType);
    }

    @SuppressWarnings("unchecked")
    private <T> List<Object> findAllTyped(Class<T> entityType) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            JpaCriteriaQuery<T> query = session.getCriteriaBuilder().createQuery(entityType);
            JpaRoot<T> root = query.from(entityType);
            query.select(root);
            List<T> results = session.createQuery(query).list();
            for (T result : results) {
                hydrateCollectionMembers(session, result);
                hydrateGeoPoints(session, result, new IdentityHashMap<>());
            }
            return (List<Object>) (List<?>) results;
        }
    }

    @Override
    public void deleteById(Class<?> entityType, UUID id) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                Object entity = session.find(entityType, id);
                if (entity != null) {
                    cascadeDeleteCollectionMembers(session, entityType.getName(), id);
                    session.remove(entity);
                }
                deleteVectors(session, entityType.getName(), id);
                deleteGeoPoints(session, entityType.getName(), id);
                tx.commit();
            } catch (RuntimeException e) {
                tx.rollback();
                throw e;
            }
        }
    }

    @Override
    public List<Object> findNearestByFieldVector(
            Class<?> entityType, String fieldName, EmbeddingVector reference, int limit) {
        SessionFactory factory = sessionFactory();
        List<UUID> rankedIds;
        try (Session session = factory.openSession()) {
            rankedIds = session.doReturningWork(connection -> {
                String table = ensureFieldVectorTable(connection, reference.modelId(), reference.dims());
                return rankIds(connection, table, entityType, fieldName, reference, limit);
            });
        }
        return hydrate(factory, entityType, rankedIds);
    }

    @Override
    public List<Object> findNearestBySummaryVector(Class<?> entityType, EmbeddingVector reference, int limit) {
        SessionFactory factory = sessionFactory();
        List<UUID> rankedIds;
        try (Session session = factory.openSession()) {
            rankedIds = session.doReturningWork(connection -> {
                String table = ensureSummaryVectorTable(connection, reference.modelId(), reference.dims());
                return rankIds(connection, table, entityType, null, reference, limit);
            });
        }
        return hydrate(factory, entityType, rankedIds);
    }

    // ---- ordinary derived finders (OMI-138): JPA Criteria translation --------------------------

    /** Rejects, at repository-creation time, a derived finder this backend can't translate. Nested filter
     *  paths traverse both singular {@code @Entity} associations (Criteria joins) and to-many/JavAI-collection
     *  fields (resolved through {@code javai_collection_members} to an id set). Leaf rules depend on the
     *  operator: emptiness ({@code IsEmpty}/{@code IsNotEmpty}) needs a collection field; geo ({@code Near}/
     *  {@code Within}) needs a {@code Point} field; every other operator needs a mapped scalar column. Sort
     *  is limited to a singular scalar path (Criteria can join+order it, but not through a to-many). */
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
                if (field.getType().isAnnotationPresent(Entity.class)) {
                    owner = field.getType(); // singular association
                } else if (DerivedFinderQuery.isToMany(field)
                        && DerivedFinderQuery.collectionMemberType(field).isAnnotationPresent(Entity.class)) {
                    owner = DerivedFinderQuery.collectionMemberType(field); // to-many association
                } else {
                    throw new IllegalArgumentException("Postgres derived finder cannot traverse '" + segments[i]
                            + "' on " + owner.getName() + " -- an intermediate segment must be a singular @Entity "
                            + "or a to-many collection of @Entity; this field is neither.");
                }
            } else {
                validateLeaf(owner, field, type);
            }
        }
    }

    private static void validateLeaf(Class<?> owner, Field field, Part.Type type) {
        switch (type) {
            case IS_EMPTY, IS_NOT_EMPTY -> {
                if (!DerivedFinderQuery.isToMany(field)) {
                    throw new IllegalArgumentException("Postgres IsEmpty/IsNotEmpty needs a collection field -- '"
                            + field.getName() + "' on " + owner.getName() + " is not one.");
                }
            }
            case NEAR, WITHIN -> {
                if (!Point.class.isAssignableFrom(field.getType())) {
                    throw new IllegalArgumentException("Postgres Near/Within needs a Point field -- '"
                            + field.getName() + "' on " + owner.getName() + " is "
                            + field.getType().getSimpleName() + ".");
                }
            }
            case EXISTS -> {
                // Presence: valid on any field (scalar -> IS NOT NULL, collection -> non-empty).
            }
            default -> {
                if (isJavAICollectionField(field) || Point.class.isAssignableFrom(field.getType())) {
                    throw new IllegalArgumentException("Postgres derived finder cannot filter on '" + field.getName()
                            + "' of " + owner.getName() + " with " + type + " -- it's a collection/geo field, not a "
                            + "scalar column.");
                }
            }
        }
    }

    private static void validateSortPath(Class<?> entityType, String dotPath) {
        Class<?> owner = entityType;
        String[] segments = dotPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            Field field = EntityReflection.findField(owner, segments[i]);
            if (i < segments.length - 1) {
                if (!field.getType().isAnnotationPresent(Entity.class)) {
                    throw new IllegalArgumentException("Postgres derived finder can only sort through singular "
                            + "@Entity associations, not '" + segments[i] + "' on " + owner.getName() + ".");
                }
                owner = field.getType();
            } else if (isJavAICollectionField(field) || Point.class.isAssignableFrom(field.getType())) {
                throw new IllegalArgumentException("Postgres derived finder cannot sort by '" + segments[i]
                        + "' of " + owner.getName() + " -- it's a collection/geo field, not a scalar column.");
            }
        }
    }

    @Override
    public List<Object> findByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args,
            DerivedFinderQuery.Constraints constraints) {
        return findByDerivedTyped(entityType, query, args, constraints);
    }

    private <T> List<Object> findByDerivedTyped(Class<T> entityType, DerivedFinderQuery query, Object[] args,
            DerivedFinderQuery.Constraints constraints) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
            JpaCriteriaQuery<T> cq = cb.createQuery(entityType);
            JpaRoot<T> root = cq.from(entityType);
            cq.select(root);
            if (query.partTree().isDistinct()) {
                cq.distinct(true);
            }
            Predicate where = buildWhere(session, cb, root, entityType, query.boundOrGroups(args));
            if (where != null) {
                cq.where(where);
            }
            applySort(cb, cq, root, constraints.sort());
            var typed = session.createQuery(cq);
            if (constraints.skip() != null) {
                typed.setFirstResult(constraints.skip());
            }
            if (constraints.maxResults() != null) {
                typed.setMaxResults(constraints.maxResults());
            }
            List<T> results = typed.list();
            List<Object> out = new ArrayList<>(results.size());
            for (T entity : results) {
                hydrateCollectionMembers(session, entity);
                hydrateGeoPoints(session, entity, new IdentityHashMap<>());
                out.add(entity);
            }
            return out;
        }
    }

    @Override
    public long countByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        return countByDerivedTyped(entityType, query, args);
    }

    private <T> long countByDerivedTyped(Class<T> entityType, DerivedFinderQuery query, Object[] args) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
            JpaCriteriaQuery<Long> cq = cb.createQuery(Long.class);
            JpaRoot<T> root = cq.from(entityType);
            cq.select(query.partTree().isDistinct() ? cb.countDistinct(root) : cb.count(root));
            Predicate where = buildWhere(session, cb, root, entityType, query.boundOrGroups(args));
            if (where != null) {
                cq.where(where);
            }
            return session.createQuery(cq).getSingleResult();
        }
    }

    @Override
    public boolean existsByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        return countByDerivedQuery(entityType, query, args) > 0;
    }

    @Override
    public long deleteByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        // Resolve matches, then delete each through the existing deleteById path so the entity's vector rows
        // and collection-membership rows are cleaned up too -- a bulk Criteria delete would bypass both and
        // leave orphaned javai_vectors__*/javai_collection_members rows behind.
        List<Object> matches =
                findByDerivedQuery(entityType, query, args, new DerivedFinderQuery.Constraints(Sort.unsorted(), null, null));
        for (Object entity : matches) {
            deleteById(entityType, EntityReflection.readId(entity));
        }
        return matches.size();
    }

    private Predicate buildWhere(Session session, HibernateCriteriaBuilder cb, JpaRoot<?> root,
            Class<?> rootType, List<List<DerivedFinderQuery.BoundPart>> orGroups) {
        List<Predicate> orPredicates = new ArrayList<>();
        for (List<DerivedFinderQuery.BoundPart> group : orGroups) {
            List<Predicate> andPredicates = new ArrayList<>();
            for (DerivedFinderQuery.BoundPart part : group) {
                andPredicates.add(toPredicate(session, cb, root, rootType, part));
            }
            if (!andPredicates.isEmpty()) {
                orPredicates.add(cb.and(andPredicates.toArray(new Predicate[0])));
            }
        }
        return orPredicates.isEmpty() ? null : cb.or(orPredicates.toArray(new Predicate[0]));
    }

    /** A pure single-or-nested-<em>singular</em> scalar predicate stays a native Criteria expression (joins
     *  included). Anything needing a side table -- a to-many hop ({@code javai_collection_members}), geo
     *  ({@code javai_geo_points} + earthdistance), or collection emptiness -- is resolved to a set of matching
     *  root ids and expressed as {@code root.id IN (...)}, which composes with {@code AND}/{@code OR} exactly
     *  like any other predicate. */
    private Predicate toPredicate(Session session, HibernateCriteriaBuilder cb, JpaRoot<?> root,
            Class<?> rootType, DerivedFinderQuery.BoundPart part) {
        Part.Type type = part.type();
        String dotPath = part.property().toDotPath();
        boolean collectionLeaf = isCollectionLeaf(rootType, dotPath);
        boolean geo = type == Part.Type.NEAR || type == Part.Type.WITHIN;
        boolean emptiness = type == Part.Type.IS_EMPTY || type == Part.Type.IS_NOT_EMPTY
                || (type == Part.Type.EXISTS && collectionLeaf);
        boolean hasToMany = DerivedFinderQuery.firstToManySplit(rootType, dotPath).isPresent();
        if (geo || emptiness || hasToMany) {
            Set<UUID> ids = rootIdsMatching(session, rootType, part);
            Path<?> idPath = root.get(EntityReflection.idField(rootType).getName());
            return ids.isEmpty() ? cb.disjunction() : idPath.in(ids);
        }
        Path<?> path = resolvePath(root, dotPath);
        if (type == Part.Type.EXISTS) {
            return cb.isNotNull(path);
        }
        return scalarPredicate(cb, path, part);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate scalarPredicate(
            HibernateCriteriaBuilder cb, Path path, DerivedFinderQuery.BoundPart part) {
        List<Object> a = part.arguments();
        boolean ic = part.ignoreCase();
        return switch (part.type()) {
            case SIMPLE_PROPERTY -> equalPredicate(cb, path, a.get(0), ic);
            case NEGATING_SIMPLE_PROPERTY -> cb.not(equalPredicate(cb, path, a.get(0), ic));
            case GREATER_THAN, AFTER -> cb.greaterThan(path, (Comparable) a.get(0));
            case GREATER_THAN_EQUAL -> cb.greaterThanOrEqualTo(path, (Comparable) a.get(0));
            case LESS_THAN, BEFORE -> cb.lessThan(path, (Comparable) a.get(0));
            case LESS_THAN_EQUAL -> cb.lessThanOrEqualTo(path, (Comparable) a.get(0));
            case BETWEEN -> cb.between(path, (Comparable) a.get(0), (Comparable) a.get(1));
            case IS_NULL -> cb.isNull(path);
            case IS_NOT_NULL -> cb.isNotNull(path);
            case LIKE -> likePredicate(cb, path, String.valueOf(a.get(0)), ic);
            case NOT_LIKE -> cb.not(likePredicate(cb, path, String.valueOf(a.get(0)), ic));
            case STARTING_WITH -> likePredicate(cb, path, a.get(0) + "%", ic);
            case ENDING_WITH -> likePredicate(cb, path, "%" + a.get(0), ic);
            case CONTAINING -> likePredicate(cb, path, "%" + a.get(0) + "%", ic);
            case NOT_CONTAINING -> cb.not(likePredicate(cb, path, "%" + a.get(0) + "%", ic));
            case REGEX -> cb.isTrue(cb.function("regexp_like", Boolean.class,
                    path.as(String.class), cb.literal(String.valueOf(a.get(0))), cb.literal(ic ? "i" : "c")));
            case IN -> path.in((Collection<?>) a.get(0));
            case NOT_IN -> cb.not(path.in((Collection<?>) a.get(0)));
            case TRUE -> cb.isTrue(path);
            case FALSE -> cb.isFalse(path);
            default -> throw new IllegalArgumentException(
                    "Unsupported derived-query operator " + part.type() + " for the Postgres backend.");
        };
    }

    // ---- id-set resolution for to-many / geo / emptiness predicates ---------------------------

    private static boolean isCollectionLeaf(Class<?> rootType, String dotPath) {
        Class<?> owner = rootType;
        String[] segments = dotPath.split("\\.");
        for (int i = 0; i < segments.length - 1; i++) {
            Field field = EntityReflection.findField(owner, segments[i]);
            owner = DerivedFinderQuery.isToMany(field)
                    ? DerivedFinderQuery.collectionMemberType(field) : field.getType();
        }
        return DerivedFinderQuery.isToMany(EntityReflection.findField(owner, segments[segments.length - 1]));
    }

    /** Resolves the set of {@code rootType} ids matching {@code part}'s (nested / geo / emptiness) predicate:
     *  compute the ids of the leaf's owning type that satisfy the leaf condition, then walk the path back to
     *  the root, mapping ids across each hop (a singular hop via a Criteria {@code assoc.id IN (...)}, a
     *  to-many hop via {@code javai_collection_members}). */
    private Set<UUID> rootIdsMatching(Session session, Class<?> rootType, DerivedFinderQuery.BoundPart part) {
        String[] segments = part.property().toDotPath().split("\\.");
        Class<?>[] ownerTypes = new Class<?>[segments.length]; // ownerTypes[i] owns segments[i]
        Class<?> type = rootType;
        for (int i = 0; i < segments.length; i++) {
            ownerTypes[i] = type;
            Field field = EntityReflection.findField(type, segments[i]);
            type = DerivedFinderQuery.isToMany(field)
                    ? DerivedFinderQuery.collectionMemberType(field) : field.getType();
        }
        Class<?> leafOwnerType = ownerTypes[segments.length - 1];
        Set<UUID> ids = leafOwnerIds(session, leafOwnerType, segments[segments.length - 1], part);
        for (int i = segments.length - 2; i >= 0; i--) {
            Class<?> parentType = ownerTypes[i];
            Field segField = EntityReflection.findField(parentType, segments[i]);
            ids = DerivedFinderQuery.isToMany(segField)
                    ? membershipOwnerIds(session, parentType, segments[i], ids)
                    : singularParentIds(session, parentType, segments[i], ownerTypes[i + 1], ids);
        }
        return ids;
    }

    private Set<UUID> leafOwnerIds(
            Session session, Class<?> type, String field, DerivedFinderQuery.BoundPart part) {
        return switch (part.type()) {
            case NEAR, WITHIN -> geoOwnerIds(session, type, field, DerivedFinderQuery.geoCircle(part));
            case IS_NOT_EMPTY, EXISTS -> ownersWithMembers(session, type, field);
            case IS_EMPTY -> {
                Set<UUID> all = allIds(session, type);
                all.removeAll(ownersWithMembers(session, type, field));
                yield all;
            }
            default -> selectIds(session, type, (cb, root) -> scalarPredicate(cb, root.get(field), part));
        };
    }

    private Set<UUID> geoOwnerIds(
            Session session, Class<?> ownerType, String field, DerivedFinderQuery.GeoCircle geo) {
        return session.doReturningWork(connection -> {
            Set<UUID> ids = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT owner_id FROM javai_geo_points WHERE owner_type = ? AND field_name = ? "
                            + "AND earth_distance(ll_to_earth(latitude, longitude), ll_to_earth(?, ?)) <= ?")) {
                statement.setString(1, ownerType.getName());
                statement.setString(2, field);
                statement.setDouble(3, geo.latitude());
                statement.setDouble(4, geo.longitude());
                statement.setDouble(5, geo.radiusMeters());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add((UUID) resultSet.getObject(1));
                    }
                }
            }
            return ids;
        });
    }

    private Set<UUID> ownersWithMembers(Session session, Class<?> ownerType, String field) {
        return session.doReturningWork(connection -> {
            Set<UUID> ids = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT owner_id FROM "
                    + "javai_collection_members WHERE owner_type = ? AND field_name = ?")) {
                statement.setString(1, ownerType.getName());
                statement.setString(2, field);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add((UUID) resultSet.getObject(1));
                    }
                }
            }
            return ids;
        });
    }

    private Set<UUID> membershipOwnerIds(
            Session session, Class<?> ownerType, String field, Set<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return session.doReturningWork(connection -> {
            Set<UUID> ids = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT owner_id FROM "
                    + "javai_collection_members WHERE owner_type = ? AND field_name = ? AND member_id = ANY(?)")) {
                statement.setString(1, ownerType.getName());
                statement.setString(2, field);
                statement.setArray(3, connection.createArrayOf("uuid", memberIds.toArray()));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        ids.add((UUID) resultSet.getObject(1));
                    }
                }
            }
            return ids;
        });
    }

    private Set<UUID> singularParentIds(
            Session session, Class<?> parentType, String field, Class<?> childType, Set<UUID> childIds) {
        if (childIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        String childIdName = EntityReflection.idField(childType).getName();
        return selectIds(session, parentType, (cb, root) -> root.get(field).get(childIdName).in(childIds));
    }

    private Set<UUID> allIds(Session session, Class<?> type) {
        return selectIds(session, type, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Set<UUID> selectIds(Session session, Class<?> type,
            java.util.function.BiFunction<HibernateCriteriaBuilder, JpaRoot<?>, Predicate> where) {
        HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
        JpaCriteriaQuery<UUID> cq = cb.createQuery(UUID.class);
        JpaRoot root = cq.from(type);
        cq.select(root.get(EntityReflection.idField(type).getName()));
        if (where != null) {
            cq.where(where.apply(cb, root));
        }
        return new LinkedHashSet<>(session.createQuery(cq).list());
    }

    // ---- geo Point fields: out-of-band storage in javai_geo_points ----------------------------

    /** Upserts (or clears) the {@code Point} fields of every reachable {@code @Entity} into
     *  {@code javai_geo_points}. Point fields are {@code @Transient} (see {@link #isBackendManagedField}),
     *  so -- exactly like JavAI collection fields -- their value lives only on the caller's original object,
     *  which is why {@link #save} passes {@code entity}, not the merged instance. */
    private void syncGeoPoints(Session session, Object entity, Map<Object, Boolean> visited) {
        if (entity == null || visited.put(entity, Boolean.TRUE) != null) {
            return;
        }
        if (entity.getClass().isAnnotationPresent(Entity.class)) {
            UUID id = EntityReflection.readId(entity);
            String ownerType = entity.getClass().getName();
            for (Field field : EntityReflection.allFields(entity.getClass())) {
                if (Point.class.isAssignableFrom(field.getType())) {
                    Point point = (Point) EntityReflection.readField(entity, field.getName());
                    String fieldName = field.getName();
                    session.doWork(connection -> upsertGeoPoint(connection, ownerType, id, fieldName, point));
                }
            }
        }
        for (Object related : reachableRelated(entity)) {
            syncGeoPoints(session, related, visited);
        }
    }

    /** Populates the {@code Point} fields of an already-loaded entity (and its reachable related entities)
     *  from {@code javai_geo_points}, mirroring {@link #hydrateCollectionMembers}. */
    private void hydrateGeoPoints(Session session, Object entity, Map<Object, Boolean> visited) {
        if (entity == null || visited.put(entity, Boolean.TRUE) != null) {
            return;
        }
        if (entity.getClass().isAnnotationPresent(Entity.class)) {
            UUID id = EntityReflection.readId(entity);
            String ownerType = entity.getClass().getName();
            for (Field field : EntityReflection.allFields(entity.getClass())) {
                if (Point.class.isAssignableFrom(field.getType())) {
                    Point point = session.doReturningWork(
                            connection -> readGeoPoint(connection, ownerType, id, field.getName()));
                    if (point != null) {
                        field.setAccessible(true);
                        try {
                            field.set(entity, point);
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException("Cannot write Point field " + field, e);
                        }
                    }
                }
            }
        }
        for (Object related : reachableRelated(entity)) {
            hydrateGeoPoints(session, related, visited);
        }
    }

    /** The singular related entities, collection elements, and map values reachable through {@code entity}'s
     *  own fields -- the graph {@link #syncGeoPoints}/{@link #hydrateGeoPoints} recurse over. */
    private static List<Object> reachableRelated(Object entity) {
        List<Object> related = new ArrayList<>();
        for (Field field : EntityReflection.allFields(entity.getClass())) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + entity.getClass(), e);
            }
            if (value instanceof Map<?, ?> map) {
                related.addAll(map.values());
            } else if (value instanceof Collection<?> collection) {
                related.addAll(collection);
            } else if (value != null && value.getClass().isAnnotationPresent(Entity.class)) {
                related.add(value);
            }
        }
        return related;
    }

    private static void upsertGeoPoint(
            Connection connection, String ownerType, UUID ownerId, String fieldName, Point point) throws SQLException {
        if (point == null) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM javai_geo_points "
                    + "WHERE owner_type = ? AND owner_id = ? AND field_name = ?")) {
                statement.setString(1, ownerType);
                statement.setObject(2, ownerId);
                statement.setString(3, fieldName);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO javai_geo_points (owner_type, owner_id, field_name, longitude, latitude) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (owner_type, owner_id, field_name) "
                        + "DO UPDATE SET longitude = EXCLUDED.longitude, latitude = EXCLUDED.latitude")) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, fieldName);
            statement.setDouble(4, point.getX()); // longitude
            statement.setDouble(5, point.getY()); // latitude
            statement.executeUpdate();
        }
    }

    private static Point readGeoPoint(
            Connection connection, String ownerType, UUID ownerId, String fieldName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT longitude, latitude FROM "
                + "javai_geo_points WHERE owner_type = ? AND owner_id = ? AND field_name = ?")) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, fieldName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? new Point(resultSet.getDouble(1), resultSet.getDouble(2)) : null;
            }
        }
    }

    private static void deleteGeoPoints(Session session, String ownerType, UUID id) {
        session.doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM javai_geo_points WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, ownerType);
                statement.setObject(2, id);
                statement.executeUpdate();
            }
        });
    }

    private static Predicate equalPredicate(HibernateCriteriaBuilder cb, Path<?> path, Object value, boolean ignoreCase) {
        if (ignoreCase && value instanceof String s) {
            return cb.equal(cb.lower(path.as(String.class)), s.toLowerCase(Locale.ROOT));
        }
        return cb.equal(path, value);
    }

    private static Predicate likePredicate(HibernateCriteriaBuilder cb, Path<?> path, String pattern, boolean ignoreCase) {
        Expression<String> asString = path.as(String.class);
        return ignoreCase
                ? cb.like(cb.lower(asString), pattern.toLowerCase(Locale.ROOT))
                : cb.like(asString, pattern);
    }

    private static void applySort(
            HibernateCriteriaBuilder cb, JpaCriteriaQuery<?> cq, JpaRoot<?> root, Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return;
        }
        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            Path<?> path = resolvePath(root, order.getProperty());
            Expression<?> expression = order.isIgnoreCase() ? cb.lower(path.as(String.class)) : path;
            orders.add(order.isAscending() ? cb.asc(expression) : cb.desc(expression));
        }
        cq.orderBy(orders);
    }

    /** Navigates a (possibly nested, dot-separated) property path from {@code root}. For a singular
     *  {@code @Entity} association segment, Criteria navigation implies the inner join automatically. */
    private static Path<?> resolvePath(JpaRoot<?> root, String dotPath) {
        Path<?> path = root;
        for (String segment : dotPath.split("\\.")) {
            path = path.get(segment);
        }
        return path;
    }

    // ---- vector read/write -------------------------------------------------------------------

    private void writeVectors(Session session, Class<?> entityType, Object entity) {
        // Not every persisted @Entity is @JavAIVectorizable -- a @Taggable-only entity (no embedding of
        // its own; see javai-tagging's own doc/spec/tagging.md "Orthogonality" section) is fully valid to
        // save through this same JavAIRepository path, it just has nothing to write here.
        if (!(entity instanceof JavAIVectorizable vectorizable)) {
            return;
        }
        UUID id = EntityReflection.readId(entity);
        String ownerType = entityType.getName();

        session.doWork(connection -> {
            for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
                EmbeddingVector vector = vectorizable.fieldVector(fieldName);
                String table = ensureFieldVectorTable(connection, vector.modelId(), vector.dims());
                upsertVector(connection, table, ownerType, id, fieldName, vector);
            }
            EmbeddingVector combined = vectorizable.vector();
            String combinedTable = ensureFieldVectorTable(connection, combined.modelId(), combined.dims());
            upsertVector(connection, combinedTable, ownerType, id, COMBINED_VECTOR_FIELD, combined);

            EmbeddingVector summary = vectorizable.summaryVector();
            String summaryTable = ensureSummaryVectorTable(connection, summary.modelId(), summary.dims());
            upsertSummaryVector(connection, summaryTable, ownerType, id, summary);
        });
    }

    // ---- relational fields: singular (ordinary Hibernate @OneToOne) + collection (this backend's own) --

    /** Recursively assigns a random {@code UUID} to any reachable entity (this one, a singular related
     *  entity, a collection element, or a map value) whose {@code @Id} is still null -- there's no
     *  {@code @GeneratedValue}, so every entity's identity is application-assigned, cascaded relations
     *  included. Must run before {@code session.merge(...)}, not after: Hibernate needs the id in place to
     *  cascade-insert a related entity at all. */
    private static void ensureIdsAssigned(Object entity, Map<Object, Boolean> visited) {
        if (entity == null || visited.put(entity, Boolean.TRUE) != null) {
            return;
        }
        if (entity.getClass().isAnnotationPresent(Entity.class) && EntityReflection.readId(entity) == null) {
            EntityReflection.writeId(entity, UUID.randomUUID());
        }
        for (Field field : EntityReflection.allFields(entity.getClass())) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + entity.getClass(), e);
            }
            if (value instanceof Map<?, ?> map) {
                for (Object element : map.values()) {
                    ensureIdsAssigned(element, visited);
                }
            } else if (value instanceof Collection<?> collection) {
                for (Object element : collection) {
                    ensureIdsAssigned(element, visited);
                }
            } else if (value != null && value.getClass().isAnnotationPresent(Entity.class)) {
                ensureIdsAssigned(value, visited);
            }
        }
    }

    /** Writes vectors for every *singular* related entity Hibernate's own {@code @OneToOne(cascade=ALL)}
     *  already persisted as part of {@code session.merge(...)} -- Hibernate handles the relational side of
     *  that automatically, but has no idea this project's vector tables exist, so this backend still has
     *  to write them itself. Collection/map-shaped fields are skipped here and handled separately by
     *  {@link #syncCollectionMembers}, which writes each element's/value's vectors right after persisting it
     *  -- {@code JavAIArrayList}/{@code JavAILinkedHashSet}/{@code JavAILinkedHashMap} are themselves
     *  {@code JavAIVectorizable} (that's what makes their own {@code centroid()}/{@code vector()} work), so
     *  without this exclusion the collection/map field itself would be mistaken for a related entity and
     *  fail {@code EntityReflection.readId} since it has no {@code @Id}. */
    private void writeVectorsForRelatedEntities(Session session, Object entity) {
        for (Field field : EntityReflection.allFields(entity.getClass())) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + entity.getClass(), e);
            }
            if (value instanceof Collection<?> || value instanceof Map<?, ?>) {
                continue;
            }
            if (value instanceof JavAIVectorizable vectorizable) {
                writeVectors(session, value.getClass(), vectorizable);
            }
        }
    }

    /** Persists every element of every {@code Collection}-typed field, and every value of every
     *  {@code Map}-typed field (each merged as its own entity, its own vectors written if it's
     *  {@code JavAIVectorizable}), then replaces that field's membership rows in
     *  {@code javai_collection_members} wholesale -- simplest correct way to handle removals/reordering
     *  without diffing old vs. new membership by hand. Elements/values that aren't real JPA entities (no
     *  {@code @Entity}) are silently skipped -- nothing for this mechanism to do with them. */
    private void syncCollectionMembers(Session session, Class<?> entityType, Object entity) {
        UUID ownerId = EntityReflection.readId(entity);
        String ownerType = entityType.getName();
        for (Field field : EntityReflection.allFields(entityType)) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + entityType, e);
            }
            String fieldName = field.getName();
            List<MemberWrite> persistedMembers;
            if (value instanceof Map<?, ?> map) {
                persistedMembers = new ArrayList<>();
                for (Map.Entry<?, ?> mapEntry : map.entrySet()) {
                    Object element = mapEntry.getValue();
                    if (element == null || !element.getClass().isAnnotationPresent(Entity.class)) {
                        continue;
                    }
                    persistedMembers.add(new MemberWrite(String.valueOf(mapEntry.getKey()), session.merge(element)));
                }
            } else if (value instanceof Collection<?> collection) {
                persistedMembers = new ArrayList<>();
                for (Object element : collection) {
                    if (!element.getClass().isAnnotationPresent(Entity.class)) {
                        continue;
                    }
                    persistedMembers.add(new MemberWrite(null, session.merge(element)));
                }
            } else {
                continue;
            }
            session.flush();
            replaceCollectionMembership(session, ownerType, ownerId, fieldName, persistedMembers);
            for (MemberWrite member : persistedMembers) {
                if (member.entity() instanceof JavAIVectorizable vectorizable) {
                    writeVectors(session, member.entity().getClass(), vectorizable);
                }
            }
        }
    }

    /** One persisted collection/map member -- {@code key} is {@code null} for a {@code Collection} member,
     *  or the map key (stringified) for a {@code Map} value. */
    private record MemberWrite(String key, Object entity) {
    }

    private static void replaceCollectionMembership(
            Session session, String ownerType, UUID ownerId, String fieldName, List<MemberWrite> members) {
        session.doWork(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM javai_collection_members "
                    + "WHERE owner_type = ? AND owner_id = ? AND field_name = ?")) {
                delete.setString(1, ownerType);
                delete.setObject(2, ownerId);
                delete.setString(3, fieldName);
                delete.executeUpdate();
            }
            int ordinal = 0;
            for (MemberWrite member : members) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO javai_collection_members "
                                + "(owner_type, owner_id, field_name, member_type, member_id, member_key, ordinal) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, ownerType);
                    insert.setObject(2, ownerId);
                    insert.setString(3, fieldName);
                    insert.setString(4, member.entity().getClass().getName());
                    insert.setObject(5, EntityReflection.readId(member.entity()));
                    insert.setString(6, member.key());
                    insert.setInt(7, ordinal++);
                    insert.executeUpdate();
                }
            }
        });
    }

    /** Populates every {@code Collection}/{@code Map}-typed field of an already-loaded entity from
     *  {@code javai_collection_members}, in ordinal order -- adding into whatever collection/map instance
     *  the entity's own no-arg constructor already created (a real {@code JavAIArrayList}/
     *  {@code JavAILinkedHashMap}, dirty-tracking intact), never replacing the field's value, so a
     *  {@code final} field works fine here. */
    private void hydrateCollectionMembers(Session session, Object entity) {
        Class<?> entityType = entity.getClass();
        UUID ownerId = EntityReflection.readId(entity);
        String ownerType = entityType.getName();
        for (Field field : EntityReflection.allFields(entityType)) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + entityType, e);
            }
            String fieldName = field.getName();
            List<MemberRef> members = session.doReturningWork(connection ->
                    findCollectionMembers(connection, ownerType, ownerId, fieldName));
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> stringKeyedMap = (Map<String, Object>) map;
                for (MemberRef member : members) {
                    Object loaded = session.find(resolveMemberType(member), member.id());
                    if (loaded != null) {
                        stringKeyedMap.put(member.key(), loaded);
                    }
                }
            } else if (value instanceof Collection<?>) {
                @SuppressWarnings("unchecked")
                Collection<Object> collection = (Collection<Object>) value;
                for (MemberRef member : members) {
                    Object loaded = session.find(resolveMemberType(member), member.id());
                    if (loaded != null) {
                        collection.add(loaded);
                    }
                }
            }
        }
    }

    private static Class<?> resolveMemberType(MemberRef member) {
        try {
            return Class.forName(member.type());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot resolve persisted member type " + member.type(), e);
        }
    }

    /** {@code key} is {@code null} for a member of a {@code Collection} field, or the original {@code Map}
     *  key ({@code String}-typed, per this backend's own limitation -- see class javadoc) for a member of
     *  a {@code Map} field. */
    private record MemberRef(String type, UUID id, String key) {
    }

    private static List<MemberRef> findCollectionMembers(
            Connection connection, String ownerType, UUID ownerId, String fieldName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT member_type, member_id, member_key "
                + "FROM javai_collection_members WHERE owner_type = ? AND owner_id = ? AND field_name = ? "
                + "ORDER BY ordinal")) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, fieldName);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MemberRef> members = new ArrayList<>();
                while (resultSet.next()) {
                    members.add(new MemberRef(
                            resultSet.getString(1), (UUID) resultSet.getObject(2), resultSet.getString(3)));
                }
                return members;
            }
        }
    }

    /** Mirrors the {@code cascade = CascadeType.ALL} on the singular {@code @OneToOne} fields: a
     *  collection member conceptually belongs to exactly one owner in this domain, so deleting the owner
     *  deletes its members (and their own vector rows) too, not just the membership record -- avoiding
     *  orphaned rows accumulating across every {@code deleteById}. */
    private void cascadeDeleteCollectionMembers(Session session, String ownerType, UUID ownerId) {
        List<MemberRef> allMembers = session.doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT member_type, member_id, member_key "
                    + "FROM javai_collection_members WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, ownerType);
                statement.setObject(2, ownerId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<MemberRef> members = new ArrayList<>();
                    while (resultSet.next()) {
                        members.add(new MemberRef(
                                resultSet.getString(1), (UUID) resultSet.getObject(2), resultSet.getString(3)));
                    }
                    return members;
                }
            }
        });
        for (MemberRef member : allMembers) {
            Object memberEntity = session.find(resolveMemberType(member), member.id());
            if (memberEntity != null) {
                session.remove(memberEntity);
            }
            deleteVectors(session, member.type(), member.id());
            deleteGeoPoints(session, member.type(), member.id());
        }
        session.doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM javai_collection_members WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, ownerType);
                statement.setObject(2, ownerId);
                statement.executeUpdate();
            }
        });
    }

    private static void upsertVector(Connection connection, String table, String ownerType, UUID ownerId,
            String fieldName, EmbeddingVector vector) throws SQLException {
        String sql = "INSERT INTO " + table + " (owner_type, owner_id, field_name, model_id, dims, vector, computed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::vector, ?) "
                + "ON CONFLICT (owner_type, owner_id, field_name) "
                + "DO UPDATE SET dims = EXCLUDED.dims, vector = EXCLUDED.vector, computed_at = EXCLUDED.computed_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, fieldName);
            statement.setString(4, vector.modelId());
            statement.setInt(5, vector.dims());
            statement.setString(6, toVectorLiteral(vector.values()));
            statement.setTimestamp(7, Timestamp.from(vector.computedAt()));
            statement.executeUpdate();
        }
    }

    private static void upsertSummaryVector(Connection connection, String table, String ownerType, UUID ownerId,
            EmbeddingVector vector) throws SQLException {
        String sql = "INSERT INTO " + table + " (owner_type, owner_id, model_id, dims, vector, computed_at) "
                + "VALUES (?, ?, ?, ?, ?::vector, ?) "
                + "ON CONFLICT (owner_type, owner_id) "
                + "DO UPDATE SET dims = EXCLUDED.dims, vector = EXCLUDED.vector, computed_at = EXCLUDED.computed_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, vector.modelId());
            statement.setInt(4, vector.dims());
            statement.setString(5, toVectorLiteral(vector.values()));
            statement.setTimestamp(6, Timestamp.from(vector.computedAt()));
            statement.executeUpdate();
        }
    }

    /** Deletes {@code (ownerType, id)}'s rows from *every* per-model table that currently exists --
     *  queried from the catalog, not this backend instance's in-memory {@link #knownTables}, since that
     *  only reflects tables created during this process's own lifetime; a table created by an earlier
     *  process run (a model used before the most recent restart) is just as real and just as much this
     *  entity's data to clean up. */
    private static void deleteVectors(Session session, String ownerType, UUID id) {
        session.doWork(connection -> {
            for (String table : findAllVectorTables(connection)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE owner_type = ? AND owner_id = ?")) {
                    statement.setString(1, ownerType);
                    statement.setObject(2, id);
                    statement.executeUpdate();
                }
            }
        });
    }

    private static List<String> findAllVectorTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND (table_name LIKE 'javai\\_vectors\\_\\_%' ESCAPE '\\' "
                        + "OR table_name LIKE 'javai\\_summary\\_vectors\\_\\_%' ESCAPE '\\')");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    /** Rank-then-hydrate's first half: ANN search entirely within one model's own vector table, bounded by
     *  LIMIT, before any of the entity's own table is touched. {@code fieldName} is null for the summary
     *  table, which has no per-field dimension. */
    private static List<UUID> rankIds(Connection connection, String table, Class<?> entityType, String fieldName,
            EmbeddingVector reference, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT owner_id FROM ").append(table)
                .append(" WHERE owner_type = ?");
        if (fieldName != null) {
            sql.append(" AND field_name = ?");
        }
        sql.append(" ORDER BY vector <=> ?::vector LIMIT ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, entityType.getName());
            if (fieldName != null) {
                statement.setString(index++, fieldName);
            }
            statement.setString(index++, toVectorLiteral(reference.values()));
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (resultSet.next()) {
                    ids.add((UUID) resultSet.getObject("owner_id"));
                }
                return ids;
            }
        }
    }

    /** Second half: a targeted load of exactly the ranked ids, one {@code find} per id (bounded by
     *  {@code limit}, typically small) -- simpler and just as correct as a batch multi-load API for this
     *  volume, and trivially preserves rank order without a separate re-sort step. */
    private List<Object> hydrate(SessionFactory factory, Class<?> entityType, List<UUID> rankedIds) {
        try (Session session = factory.openSession()) {
            List<Object> results = new ArrayList<>(rankedIds.size());
            for (UUID id : rankedIds) {
                Object entity = session.find(entityType, id);
                if (entity != null) {
                    hydrateCollectionMembers(session, entity);
                    hydrateGeoPoints(session, entity, new IdentityHashMap<>());
                    results.add(entity);
                }
            }
            return results;
        }
    }

    /** pgvector's own text input format for a vector literal, e.g. {@code "[0.1,0.2,0.3]"} -- avoids
     *  needing a JDBC-level PGobject type (and the connection-unwrapping uncertainty that comes with one
     *  under a layer like Hibernate) just to bind a vector-shaped query parameter. */
    private static String toVectorLiteral(float[] values) {
        StringBuilder literal = new StringBuilder(values.length * 8).append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(values[i]);
        }
        return literal.append(']').toString();
    }

    /** Creates {@code javai_vectors__<model>} (fixed to {@code dims} from the moment it's created -- known
     *  upfront now, since only one model's vectors will ever land in this specific table) if it doesn't
     *  already exist, and returns its name. Deliberately re-checked on every call rather than cached after
     *  the first success: this DDL runs inside the same transaction as the rest of {@code save()}, and an
     *  earlier in-memory "already created" cache surviving a *later* failure in that same transaction --
     *  which rolls the CREATE TABLE back too, DDL being transactional in Postgres -- left the cache
     *  permanently out of sync with reality, breaking every subsequent save() for the rest of this backend
     *  instance's lifetime. {@code CREATE TABLE IF NOT EXISTS} is already a cheap, idempotent catalog
     *  check; there's no correctness-safe way to skip re-running it. */
    private static String ensureFieldVectorTable(Connection connection, String modelId, int dims) throws SQLException {
        String table = FIELD_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "owner_type   varchar(255) NOT NULL,"
                    + "owner_id     uuid         NOT NULL,"
                    + "field_name   varchar(128) NOT NULL,"
                    + "model_id     varchar(128) NOT NULL,"
                    + "dims         integer      NOT NULL,"
                    + "vector       vector(" + dims + ") NOT NULL,"
                    + "computed_at  timestamptz  NOT NULL,"
                    + "PRIMARY KEY (owner_type, owner_id, field_name))");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_lookup ON " + table + " (owner_type, field_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_hnsw ON " + table + " USING hnsw (vector vector_cosine_ops)");
        }
        return table;
    }

    private static String ensureSummaryVectorTable(Connection connection, String modelId, int dims) throws SQLException {
        String table = SUMMARY_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "owner_type   varchar(255) NOT NULL,"
                    + "owner_id     uuid         NOT NULL,"
                    + "model_id     varchar(128) NOT NULL,"
                    + "dims         integer      NOT NULL,"
                    + "vector       vector(" + dims + ") NOT NULL,"
                    + "computed_at  timestamptz  NOT NULL,"
                    + "PRIMARY KEY (owner_type, owner_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_lookup ON " + table + " (owner_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_hnsw ON " + table + " USING hnsw (vector vector_cosine_ops)");
        }
        return table;
    }

    // ---- lazy bootstrap -----------------------------------------------------------------------

    private SessionFactory sessionFactory() {
        SessionFactory factory = sessionFactory;
        if (factory != null) {
            return factory;
        }
        synchronized (bootstrapLock) {
            if (sessionFactory == null) {
                sessionFactory = config.externalSessionFactory() != null
                        ? config.externalSessionFactory()
                        : buildSessionFactory();
                initializeSchema(sessionFactory);
            }
            return sessionFactory;
        }
    }

    private SessionFactory buildSessionFactory() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.url", config.postgresUrl())
                .applySetting("jakarta.persistence.jdbc.user", config.postgresUsername())
                .applySetting("jakarta.persistence.jdbc.password", config.postgresPassword())
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .build();
        MetadataSources sources = new MetadataSources(registry);
        for (Class<?> entityType : registeredEntityTypes) {
            sources.addAnnotatedClass(entityType);
        }
        String autoTransientOverrideXml = buildAutoTransientOverrideXml(registeredEntityTypes);
        if (autoTransientOverrideXml != null) {
            sources.addInputStream(new ByteArrayInputStream(autoTransientOverrideXml.getBytes(StandardCharsets.UTF_8)));
        }
        return sources.buildMetadata().buildSessionFactory();
    }

    /** Generates an in-memory JPA {@code orm.xml}-equivalent mapping document marking every JavAI collection
     *  field (see {@link #isJavAICollectionField}) of every registered entity type {@code <transient>} -- see
     *  this class's own javadoc ("No manual {@code @Transient} required") for the full rationale. Returns
     *  {@code null} (add nothing) if no registered type has any such field, to avoid feeding Hibernate an
     *  empty document for the common case where every field is already annotation-mapped correctly. */
    private static String buildAutoTransientOverrideXml(Set<Class<?>> entityTypes) {
        StringBuilder entities = new StringBuilder();
        for (Class<?> entityType : entityTypes) {
            List<Field> transientFields = EntityReflection.allFields(entityType).stream()
                    .filter(RepositoryBackendHibernatePostgres::isBackendManagedField)
                    .toList();
            if (transientFields.isEmpty()) {
                continue;
            }
            entities.append("  <entity class=\"").append(entityType.getName()).append("\">\n")
                    .append("    <attributes>\n");
            for (Field field : transientFields) {
                entities.append("      <transient name=\"").append(field.getName()).append("\"/>\n");
            }
            entities.append("    </attributes>\n  </entity>\n");
        }
        if (entities.isEmpty()) {
            return null;
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<entity-mappings xmlns=\"https://jakarta.ee/xml/ns/persistence/orm\" version=\"3.1\">\n"
                + entities
                + "</entity-mappings>\n";
    }

    /** The pgvector extension, and {@code javai_collection_members} -- unlike the per-model vector
     *  tables, this one table is created eagerly here, not lazily: it holds no vector column at all (pure
     *  owner/field/member bookkeeping), so it needs no per-model dimension to be known upfront. */
    private static void initializeSchema(SessionFactory factory) {
        try (Session session = factory.openSession()) {
            session.doWork(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS javai_collection_members (
                                owner_type   varchar(255) NOT NULL,
                                owner_id     uuid         NOT NULL,
                                field_name   varchar(128) NOT NULL,
                                member_type  varchar(255) NOT NULL,
                                member_id    uuid         NOT NULL,
                                member_key   varchar(512),
                                ordinal      integer      NOT NULL,
                                PRIMARY KEY (owner_type, owner_id, field_name, member_id)
                            )
                            """);
                    // Defensive, non-destructive upgrade path for a table created by an earlier version of
                    // this backend, before member_key existed -- CREATE TABLE IF NOT EXISTS alone wouldn't
                    // add it to an already-existing table.
                    statement.execute(
                            "ALTER TABLE javai_collection_members ADD COLUMN IF NOT EXISTS member_key varchar(512)");
                    statement.execute("CREATE INDEX IF NOT EXISTS javai_collection_members_lookup "
                            + "ON javai_collection_members (owner_type, owner_id, field_name)");

                    // Geo Point support (OMI-141): the cube + earthdistance contrib extensions (bundled with
                    // the official Postgres base image, so no PostGIS/hibernate-spatial dependency and no
                    // image swap) give great-circle distance in meters via earth_distance(ll_to_earth(...)).
                    // Point fields are @Transient and round-trip through this side table, the same out-of-band
                    // pattern javai_collection_members uses.
                    statement.execute("CREATE EXTENSION IF NOT EXISTS cube");
                    statement.execute("CREATE EXTENSION IF NOT EXISTS earthdistance");
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS javai_geo_points (
                                owner_type   varchar(255)     NOT NULL,
                                owner_id     uuid             NOT NULL,
                                field_name   varchar(128)     NOT NULL,
                                longitude    double precision NOT NULL,
                                latitude     double precision NOT NULL,
                                PRIMARY KEY (owner_type, owner_id, field_name)
                            )
                            """);
                    statement.execute("CREATE INDEX IF NOT EXISTS javai_geo_points_lookup "
                            + "ON javai_geo_points (owner_type, field_name)");
                }
            });
        }
    }
}
