package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIDirtyTracking;
import dev.xtrafe.javai.runtime.JavAIVectorizable;
import jakarta.persistence.Entity;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaRoot;

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
import java.util.List;
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
 * intact) instead of replacing it -- the same trick {@code Neo4jRepositoryBackend} already relies on for
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
final class HibernatePostgresRepositoryBackend implements RepositoryBackend {

    private static final String FIELD_VECTOR_TABLE_PREFIX = "javai_vectors__";
    private static final String SUMMARY_VECTOR_TABLE_PREFIX = "javai_summary_vectors__";

    private final JavAIPersistenceConfig config;
    private final Set<Class<?>> registeredEntityTypes = ConcurrentHashMap.newKeySet();
    private final Object bootstrapLock = new Object();
    private volatile SessionFactory sessionFactory;

    HibernatePostgresRepositoryBackend(JavAIPersistenceConfig config) {
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
     *  implements {@link JavAIDirtyTracking} -- true of every concrete {@code javai-runtime} collection
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

    @Override
    public Object save(Class<?> entityType, Object entity) {
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
                tx.commit();
                // Returns the original `entity`, not `managed`: the same reason as above -- `managed`'s
                // @Transient collection fields are left empty by merge(), so returning it would hand the
                // caller back an Article whose in-memory `comments` looks wrong immediately after save().
                // `entity` already carries every assigned id (ensureIdsAssigned mutates it in place) and is
                // what Neo4jRepositoryBackend.save() returns too, so both backends behave consistently.
                return entity;
            } catch (RuntimeException e) {
                tx.rollback();
                throw e;
            }
        }
    }

    @Override
    public Optional<Object> findById(Class<?> entityType, UUID id) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            Object entity = session.find(entityType, id);
            if (entity != null) {
                hydrateCollectionMembers(session, entity);
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

    // ---- vector read/write -------------------------------------------------------------------

    private void writeVectors(Session session, Class<?> entityType, Object entity) {
        JavAIVectorizable vectorizable = (JavAIVectorizable) entity;
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
                    .filter(HibernatePostgresRepositoryBackend::isJavAICollectionField)
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
                }
            });
        }
    }
}
