package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIDirtyTracking;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIMap;
import dev.xtrafe.javai.model.JavAISet;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataBuilder;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.WeakHashMap;
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
 * {@code PersistentMap} the instant the field is persisted. A JavAI collection field therefore takes one of
 * two supported shapes, decided entirely by its <em>declared type</em> (see {@link #isJavAICollectionField},
 * which is why the classification is 100%-confidence rather than a heuristic):
 *
 * <p><b>1. Declared by the interface, carrying a JPA annotation -- a native Hibernate association.</b>
 * {@code @OneToMany private JavAIList<Comment> comments = new JavAIArrayList<>();} -- interface-typed, and
 * non-final, since Hibernate assigns the field its own instance -- maps exactly like any other JPA
 * association: a real join table with foreign keys both ways, lazy loading, {@code mappedBy}, cascades,
 * {@code @ManyToMany} shared ownership. What Hibernate substitutes in is {@link PersistentJavAIList}/
 * {@link PersistentJavAISet}/{@link PersistentJavAIMap} -- JavAI's own {@code PersistentCollection}
 * implementations -- rather than {@code PersistentBag}/{@code PersistentSet}/{@code PersistentMap}, so the
 * field keeps its vector and dirty-tracking behavior across the substitution instead of losing it. The
 * consumer writes nothing JavAI-specific to get this; see {@link #attachJavAICollectionTypes} below.
 * Nothing about such a field touches {@code javai_collection_members}.
 *
 * <p><b>2. Declared by the concrete class, unannotated -- JavAI's own side-table storage.</b> A field
 * statically typed as a concrete JavAI collection class ({@code JavAIArrayList}/{@code JavAILinkedHashSet}/
 * {@code JavAILinkedHashMap}) can never be Hibernate-managed -- the substitution fails outright with a
 * {@code ClassCastException}, confirmed empirically. Such fields are excluded from Hibernate's mapping
 * entirely and instead round-trip through {@code javai_collection_members} -- a single, shared (not
 * per-model) table this backend owns (owner/field/member identity, an optional string key for {@code Map}
 * fields, and an ordinal for order), populated by {@link #syncCollectionMembers} on save and read back by
 * {@link #hydrateCollectionMembers} on load. Being reflective rather than proxy-based, hydration adds
 * members into whatever collection instance the entity's own no-arg constructor already created (a real
 * {@code JavAIArrayList}, full dirty-tracking intact) instead of replacing it -- the same trick
 * {@code RepositoryBackendNeo4j} already relies on for its own relationship hydration, applied here for
 * symmetry across both backends.
 *
 * <p>Both shapes are fully supported and can coexist in the same entity, field by field. The one
 * combination that cannot work -- a concrete-typed field carrying {@code @OneToMany}/{@code @ManyToMany} --
 * is rejected eagerly by {@link #validateCollectionFieldMapping} with a message naming the interface-typed
 * fix. Note this is a Postgres-only distinction: the Neo4j and MongoDB backends classify collections purely
 * by declared type and have no equivalent of a native JPA association.
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
 * <p><b>Transactions: joins the caller's, or opens its own (OMI-146).</b> Every operation resolves its
 * session through {@link #ambientSession()} rather than calling {@code openSession()} directly. When the
 * caller already owns a unit of work -- a Spring {@code @Transactional} method (shared-{@code SessionFactory}
 * mode; see {@link SpringManagedSessions}) or a {@link JavAIPI#inTransaction} body (see
 * {@link JavAITransactionScope}) -- the call runs on that session and neither commits nor closes it, so
 * several repository calls compose into one atomic unit and this backend's vector/collection/geo writes land
 * or roll back with everything else the caller did. With no ambient transaction the behavior is exactly what
 * it was before: open a session, commit, close. The distinction matters most for the write path, where
 * vectors used to be committed by a transaction of this backend's own that the caller could not roll back.
 *
 * <p><b>Physical naming: snake_case by default, and configurable (OMI-145).</b> The {@code SessionFactory}
 * this class builds applies {@link CamelCaseToUnderscoresNamingStrategy}, so {@code emailVerified} maps to
 * the column {@code email_verified} and an entity {@code TestCrew} to the table {@code test_crew} -- matching
 * Spring Boot's default and ordinary SQL convention rather than Hibernate's bare default
 * ({@code emailverified}). This is not cosmetic: pointed at a table another tool already created under the
 * conventional naming, the bare default made {@code hbm2ddl=update} add a second, differently-cased set of
 * columns beside the existing ones instead of recognizing them, and the following insert then populated
 * JavAI's copy while leaving the original {@code NOT NULL} column null. Override via
 * {@link JavAIPersistenceConfig.Builder#physicalNamingStrategy} (including pinning the pre-0.1.5 behavior
 * with {@code PhysicalNamingStrategyStandardImpl}) or the general
 * {@link JavAIPersistenceConfig.Builder#hibernateProperty} passthrough -- see
 * {@link #resolvePhysicalNamingStrategy} for the precedence between those two. None of this affects the
 * tables this backend owns itself ({@code javai_vectors__*}, {@code javai_collection_members},
 * {@code javai_geo_points}): their names and columns are literals in this class, never derived from a
 * naming strategy.
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
 * <p><b>How the native mapping is attached.</b> Shape 1 above is delivered by Hibernate's
 * {@code org.hibernate.usertype.UserCollectionType} SPI, which lets a custom {@code PersistentCollection}
 * implementation stand in for {@code PersistentBag}/{@code PersistentSet}/{@code PersistentMap}. JavAI
 * ships three ({@link JavAIListType}/{@link JavAISetType}/{@link JavAIMapType}, producing the three
 * {@code PersistentJavAI*} collections), and {@link #attachJavAICollectionTypes} binds them by walking
 * {@code metadata.getCollectionBindings()} in the window between {@code buildMetadata()} and
 * {@code buildSessionFactory()} -- the only point at which the mapping model is both fully built and still
 * mutable -- setting the type name on exactly those bindings whose field is declared by a JavAI collection
 * interface. That per-binding walk is deliberate: Hibernate's declarative
 * {@code @CollectionTypeRegistration} is keyed by {@code CollectionClassification} and would capture
 * <em>every</em> bag/set/map in the persistence unit, including plain JDK ones that must stay exactly as
 * Hibernate maps them. Doing it here rather than at the consumer's source also means the consumer writes
 * only the JPA annotation they'd write anyway -- no {@code @CollectionType}, nothing JavAI-specific.
 * See {@code javai-persistence/README.md} for the same note in context, and
 * {@code doc/ai-guidance/persistence-support-matrix.md} for the per-backend support tables.
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
        validateCollectionFieldMapping(entityType);
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

    /**
     * Fails fast, at registration time, for the two collection-mapping shapes this backend can't serve
     * correctly. Deliberately Postgres-scoped: the reflective backends classify collections purely by
     * declared type and legitimately accept both shapes.
     *
     * <p><b>1. A JPA association annotation on a <em>concrete-typed</em> JavAI collection field.</b> A
     * concrete-typed JavAI collection is mapped out-of-band through {@code javai_collection_members}, so
     * {@code @OneToMany}/{@code @ManyToMany} on one could only be silently ignored -- the developer would get
     * JavAI's own storage and its hardcoded "owner owns its members" cascade instead of the JPA semantics
     * they asked for. That's actively unsafe for {@code @ManyToMany}, where deleting one owner would delete
     * members still referenced by other owners. The fix is to declare the field by the JavAI
     * <em>interface</em> ({@code JavAIList}/{@code JavAISet}/{@code JavAIMap}), which routes it to the native
     * association path instead (see this class's own javadoc); the thrown message says exactly that.
     *
     * <p><b>2. A plain collection with no mapping annotation.</b> Hibernate can't map a bare
     * {@code Collection}/{@code Map} and would fail deep in boot with a considerably less obvious message.
     */
    private static void validateCollectionFieldMapping(Class<?> entityType) {
        for (Field field : EntityReflection.allFields(entityType)) {
            Class<?> type = field.getType();
            boolean collectionShaped = Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
            if (!collectionShaped || KnowledgeGraph.class.isAssignableFrom(type)) {
                continue;
            }
            boolean association = field.isAnnotationPresent(OneToMany.class)
                    || field.isAnnotationPresent(ManyToMany.class);
            if (isJavAICollectionField(field)) {
                if (association) {
                    throw new IllegalArgumentException("Cannot honor @OneToMany/@ManyToMany on "
                            + entityType.getName() + "." + field.getName() + ": the field is declared as the "
                            + "CONCRETE type " + field.getType().getSimpleName() + ", which Hibernate can never "
                            + "manage (it substitutes its own collection instance into the field). Declare it by "
                            + "the JavAI INTERFACE instead -- e.g. 'private JavAIList<X> " + field.getName()
                            + " = new JavAIArrayList<>();' (non-final) -- and the association becomes a native "
                            + "Hibernate one, with vectors and dirty-tracking preserved. Leave the field concrete "
                            + "and drop the annotation to keep JavAI's own side-table collection storage.");
                }
                continue; // plain JavAI collection field: mapped by this backend's own side table
            }
            if (!association && !field.isAnnotationPresent(ElementCollection.class)
                    && !field.isAnnotationPresent(Transient.class)) {
                throw new IllegalArgumentException("Postgres persistence cannot map the collection field "
                        + entityType.getName() + "." + field.getName() + " -- a plain JDK collection needs a JPA "
                        + "mapping annotation (@OneToMany/@ManyToMany for entities, @ElementCollection for "
                        + "basic/embeddable values), or @Transient to exclude it. Use a JavAI collection type "
                        + "(JavAIArrayList/JavAILinkedHashSet/JavAILinkedHashMap) if you want JavAI's own "
                        + "vector-aware collection storage instead.");
            }
        }
    }

    /** Whether removing the owner should also remove this (Hibernate-owned) association's members --
     *  {@code cascade = ALL/REMOVE}, or {@code orphanRemoval}. Drives vector/geo cleanup for members Hibernate
     *  is about to cascade-delete; without it their side-table rows would be orphaned. */
    private static boolean cascadesRemove(Field field) {
        OneToMany oneToMany = field.getAnnotation(OneToMany.class);
        if (oneToMany != null) {
            return oneToMany.orphanRemoval() || cascadeIncludesRemove(oneToMany.cascade());
        }
        ManyToMany manyToMany = field.getAnnotation(ManyToMany.class);
        return manyToMany != null && cascadeIncludesRemove(manyToMany.cascade());
    }

    private static boolean cascadeIncludesRemove(CascadeType[] cascades) {
        for (CascadeType cascade : cascades) {
            if (cascade == CascadeType.ALL || cascade == CascadeType.REMOVE) {
                return true;
            }
        }
        return false;
    }

    /** Deletes the vector/geo rows of members Hibernate is about to cascade-delete along with {@code entity}.
     *  The JavAI-collection equivalent lives in {@link #cascadeDeleteCollectionMembers}, which is driven off
     *  membership rows -- rows a natively-mapped association doesn't have. */
    private void deleteVectorsForCascadedCollectionMembers(Session session, Object entity) {
        for (Field field : EntityReflection.allFields(entity.getClass())) {
            if (isJavAICollectionField(field) || !cascadesRemove(field)) {
                continue;
            }
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + entity.getClass(), e);
            }
            Collection<?> members = value instanceof Map<?, ?> map ? map.values()
                    : value instanceof Collection<?> collection ? collection : null;
            if (members == null) {
                continue;
            }
            for (Object member : members) {
                if (member == null || !member.getClass().isAnnotationPresent(Entity.class)) {
                    continue;
                }
                UUID memberId = EntityReflection.readId(member);
                if (memberId != null) {
                    deleteVectors(session, member.getClass().getName(), memberId);
                    deleteGeoPoints(session, member.getClass().getName(), memberId);
                }
            }
        }
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
        JavAIRuntime.runWithSubgraphLockedForPersistence(entity, () -> result[0] = inTransactionalSession(session -> {
            JavAIFlushVectorListener.begin();
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
                // Last, after every write above has been flushed: covers anything Hibernate persisted by
                // cascading that the explicit walks never reach (a related entity two or more hops away).
                // Flushed, not committed: when this call joined a caller's transaction (OMI-146) the commit
                // is theirs to make, and these vector rows must land or roll back with everything else they
                // did -- which they do, since this is the caller's own session and connection.
                session.flush();
                writeVectorsForFlushedEntities(session);
                // Returns the original `entity`, not `managed`: the same reason as above -- `managed`'s
                // @Transient collection fields are left empty by merge(), so returning it would hand the
                // caller back an Article whose in-memory `comments` looks wrong immediately after save().
                // `entity` already carries every assigned id (ensureIdsAssigned mutates it in place) and is
                // what RepositoryBackendNeo4j.save() returns too, so both backends behave consistently.
                return entity;
            } finally {
                JavAIFlushVectorListener.end();
            }
        }));
        return result[0];
    }

    /**
     * Re-embeds <b>every registered entity type</b>, not just the repository's own -- re-indexing a datastore
     * against a new model has to cover the whole store, or it is left straddling two models (an
     * {@code Article} re-embedded while its {@code Comment}s are not). Then validates the result: every
     * entity that had a vector under the previously-newest model must have one under the new model, and any
     * that don't are reported by type/id rather than silently left stale.
     *
     * <p>Iterating the registered types (rather than driving the loop from the old vector table) is both
     * simpler and equally complete: an entity type has to be registered/mapped for this backend to be able to
     * load it at all, so the old table can never name a type the registry doesn't already have. The old table
     * earns its keep as the <em>manifest to validate against</em>.
     */
    @Override
    public void reindexAll() {
        Set<String> manifest = inSession(session -> session.doReturningWork(connection -> {
            String newest = newestVectorTable(connection);
            return newest == null ? Set.<String>of() : ownerKeys(connection, newest);
        }));

        for (Class<?> registered : registeredEntityTypes) {
            for (Object entity : findAll(registered)) {
                save(registered, entity);
            }
        }

        List<String> missing = inSession(session -> session.doReturningWork(connection -> {
            String newest = newestVectorTable(connection);
            Set<String> reindexed = newest == null ? Set.<String>of() : ownerKeys(connection, newest);
            return manifest.stream().filter(key -> !reindexed.contains(key)).limit(10).toList();
        }));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("reindexAll() left " + missing.size() + " or more entities "
                    + "un-reindexed under the newly configured model -- the store is now split across two "
                    + "models. Unreindexed (up to 10, as owner_type/owner_id): " + missing
                    + ". This usually means an entity type holding vectors was never registered via "
                    + "JavAIPI.repository(...) in this process.");
        }
    }

    /** The {@code javai_vectors__<model>} table most recently written to, by {@code max(computed_at)} --
     *  i.e. whichever model the store was last indexed under. {@code null} if nothing has been vectorized. */
    private static String newestVectorTable(Connection connection) throws SQLException {
        String newest = null;
        Timestamp newestAt = null;
        for (String table : findAllVectorTables(connection)) {
            if (!table.startsWith(FIELD_VECTOR_TABLE_PREFIX)) {
                continue; // summary tables mirror the field tables; one family is enough to compare
            }
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT max(computed_at) FROM " + table)) {
                if (resultSet.next()) {
                    Timestamp at = resultSet.getTimestamp(1);
                    if (at != null && (newestAt == null || at.after(newestAt))) {
                        newestAt = at;
                        newest = table;
                    }
                }
            }
        }
        return newest;
    }

    /** {@code owner_type/owner_id} keys present in a vector table -- the manifest of what was indexed. */
    private static Set<String> ownerKeys(Connection connection, String table) throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT DISTINCT owner_type, owner_id FROM " + table)) {
            while (resultSet.next()) {
                keys.add(resultSet.getString(1) + "/" + resultSet.getString(2));
            }
        }
        return keys;
    }

    @Override
    public Optional<Object> findById(Class<?> entityType, UUID id) {
        return inSession(session -> {
            Object entity = session.find(entityType, id);
            if (entity != null) {
                hydrateCollectionMembers(session, entity);
                hydrateGeoPoints(session, entity, new IdentityHashMap<>());
            }
            return Optional.ofNullable(entity);
        });
    }

    @Override
    public List<Object> findAll(Class<?> entityType) {
        return findAllTyped(entityType);
    }

    @SuppressWarnings("unchecked")
    private <T> List<Object> findAllTyped(Class<T> entityType) {
        return inSession(session -> {
            JpaCriteriaQuery<T> query = session.getCriteriaBuilder().createQuery(entityType);
            JpaRoot<T> root = query.from(entityType);
            query.select(root);
            List<T> results = session.createQuery(query).list();
            for (T result : results) {
                hydrateCollectionMembers(session, result);
                hydrateGeoPoints(session, result, new IdentityHashMap<>());
            }
            return (List<Object>) (List<?>) results;
        });
    }

    @Override
    public void deleteById(Class<?> entityType, UUID id) {
        inTransactionalSession(session -> {
            JavAIFlushVectorListener.begin();
            try {
                Object entity = session.find(entityType, id);
                if (entity != null) {
                    cascadeDeleteCollectionMembers(session, entityType.getName(), id);
                    // Members of a natively-mapped association have no membership rows, so their vector/geo
                    // rows need clearing here, before Hibernate cascades the removal itself.
                    deleteVectorsForCascadedCollectionMembers(session, entity);
                    session.remove(entity);
                }
                deleteVectors(session, entityType.getName(), id);
                deleteGeoPoints(session, entityType.getName(), id);
                // Flush the removal so Hibernate reports everything it actually cascade-deleted, then clear
                // those rows too -- catches entities removed at a depth the explicit walk above never sees.
                session.flush();
                for (JavAIFlushVectorListener.DeletedRef deleted : JavAIFlushVectorListener.current().deleted()) {
                    deleteVectors(session, deleted.ownerType(), deleted.id());
                    deleteGeoPoints(session, deleted.ownerType(), deleted.id());
                }
                return null;
            } finally {
                JavAIFlushVectorListener.end();
            }
        });
    }

    @Override
    public List<Object> findNearestByFieldVector(
            Class<?> entityType, String fieldName, EmbeddingVector reference, int limit) {
        List<UUID> rankedIds = inSession(session -> session.doReturningWork(connection -> {
            String table = ensureFieldVectorTable(connection, reference.modelId(), reference.dims());
            return rankIds(connection, table, entityType, fieldName, reference, limit);
        }));
        return hydrate(entityType, rankedIds);
    }

    @Override
    public List<Object> findNearestBySummaryVector(Class<?> entityType, EmbeddingVector reference, int limit) {
        List<UUID> rankedIds = inSession(session -> session.doReturningWork(connection -> {
            String table = ensureSummaryVectorTable(connection, reference.modelId(), reference.dims());
            return rankIds(connection, table, entityType, null, reference, limit);
        }));
        return hydrate(entityType, rankedIds);
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
        return inSession(session -> {
            HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
            JpaCriteriaQuery<T> cq = cb.createQuery(entityType);
            JpaRoot<T> root = cq.from(entityType);
            cq.select(root);
            // A to-many join multiplies root rows, so DISTINCT is required for correctness, not just for an
            // explicit Distinct keyword.
            if (query.partTree().isDistinct() || joinsToMany(entityType, query)) {
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
        });
    }

    @Override
    public long countByDerivedQuery(Class<?> entityType, DerivedFinderQuery query, Object[] args) {
        return countByDerivedTyped(entityType, query, args);
    }

    private <T> long countByDerivedTyped(Class<T> entityType, DerivedFinderQuery query, Object[] args) {
        return inSession(session -> {
            HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
            JpaCriteriaQuery<Long> cq = cb.createQuery(Long.class);
            JpaRoot<T> root = cq.from(entityType);
            boolean distinct = query.partTree().isDistinct() || joinsToMany(entityType, query);
            cq.select(distinct ? cb.countDistinct(root) : cb.count(root));
            Predicate where = buildWhere(session, cb, root, entityType, query.boundOrGroups(args));
            if (where != null) {
                cq.where(where);
            }
            return session.createQuery(cq).getSingleResult();
        });
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

        // Geo always lives in javai_geo_points, and a side-table-backed (concrete JavAI) collection has no
        // Hibernate association to join -- both still resolve to an id set. Everything else is now a real
        // Criteria query: a natively-mapped collection is a genuine association, so a join is both correct and
        // a single statement, retiring the id-set-per-hop round trips for it (OMI-142 Phase 3).
        if (geo || hasSideTableToMany(rootType, dotPath, emptiness)) {
            Set<UUID> ids = rootIdsMatching(session, rootType, part);
            Path<?> idPath = root.get(EntityReflection.idField(rootType).getName());
            return ids.isEmpty() ? cb.disjunction() : idPath.in(ids);
        }
        if (emptiness) {
            // A natively-mapped collection answers emptiness directly, with no side table involved. The leaf
            // IS the collection here, so resolveJoinedPath stops at it rather than joining through it.
            @SuppressWarnings({"unchecked", "rawtypes"})
            Expression<Collection<?>> collectionPath =
                    (Expression) resolveJoinedPath(root, rootType, dotPath);
            return type == Part.Type.IS_EMPTY ? cb.isEmpty(collectionPath) : cb.isNotEmpty(collectionPath);
        }
        Path<?> path = resolveJoinedPath(root, rootType, dotPath);
        if (type == Part.Type.EXISTS) {
            return cb.isNotNull(path);
        }
        return scalarPredicate(cb, path, part);
    }

    /** Whether any hop this predicate needs is a <em>side-table-backed</em> (concrete JavAI) collection, which
     *  Hibernate doesn't map and therefore can't join. Natively-mapped collections -- plain JDK ones and
     *  interface-typed JavAI ones alike -- return false and take the Criteria-join path instead.
     *  {@code includeLeaf} is set for emptiness, where the collection under test <em>is</em> the leaf. */
    private static boolean hasSideTableToMany(Class<?> rootType, String dotPath, boolean includeLeaf) {
        Class<?> owner = rootType;
        String[] segments = dotPath.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            Field field = EntityReflection.findField(owner, segments[i]);
            boolean leaf = i == segments.length - 1;
            if ((!leaf || includeLeaf) && DerivedFinderQuery.isToMany(field) && isJavAICollectionField(field)) {
                return true;
            }
            if (leaf) {
                return false;
            }
            owner = DerivedFinderQuery.isToMany(field)
                    ? DerivedFinderQuery.collectionMemberType(field) : field.getType();
        }
        return false;
    }

    /** Navigates a dot path by {@code join()}ing each intermediate hop. A plural attribute cannot be
     *  dereferenced with {@code get()} at all, and for a singular one an explicit join is the same inner join
     *  {@code get()} navigation would have produced -- so joining uniformly keeps the chain simple and leaves
     *  singular-path behavior unchanged. */
    private static Path<?> resolveJoinedPath(JpaRoot<?> root, Class<?> rootType, String dotPath) {
        String[] segments = dotPath.split("\\.");
        jakarta.persistence.criteria.From<?, ?> from = root;
        Class<?> owner = rootType;
        for (int i = 0; i < segments.length - 1; i++) {
            Field field = EntityReflection.findField(owner, segments[i]);
            from = from.join(segments[i]);
            owner = DerivedFinderQuery.isToMany(field)
                    ? DerivedFinderQuery.collectionMemberType(field) : field.getType();
        }
        return from.get(segments[segments.length - 1]);
    }

    /** Whether any of this query's predicates joins a to-many association, which multiplies root rows and so
     *  requires {@code DISTINCT} to keep one row per matching entity. */
    private static boolean joinsToMany(Class<?> rootType, DerivedFinderQuery query) {
        for (Part part : query.partTree().getParts()) {
            String dotPath = part.getProperty().toDotPath();
            if (hasSideTableToMany(rootType, dotPath, false)) {
                continue; // resolved as an id set, no join
            }
            Class<?> owner = rootType;
            String[] segments = dotPath.split("\\.");
            for (int i = 0; i < segments.length - 1; i++) {
                Field field = EntityReflection.findField(owner, segments[i]);
                if (DerivedFinderQuery.isToMany(field)) {
                    return true;
                }
                owner = field.getType();
            }
        }
        return false;
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
            if (isJavAICollectionField(field)) {
                continue; // syncCollectionMembers writes these members' vectors as it persists them
            }
            if (value instanceof Map<?, ?> map) {
                writeVectorsForCollectionMembers(session, map.values());
            } else if (value instanceof Collection<?> collection) {
                writeVectorsForCollectionMembers(session, collection);
            } else if (value instanceof JavAIVectorizable vectorizable) {
                writeVectors(session, value.getClass(), vectorizable);
            }
        }
    }

    /** Writes vectors for the members of a <em>Hibernate-owned</em> (plain, natively-mapped) collection.
     *  Hibernate's own cascade INSERTs those members, but it has no idea this project's vector tables exist,
     *  so without this the members of a plain {@code @OneToMany}/{@code @ManyToMany} would persist relationally
     *  yet never get a vector row -- silently breaking the "every {@code @JavAIVectorizable} written through a
     *  repository has an up-to-date, persisted vector" guarantee. JavAI collection fields are excluded by the
     *  caller because {@link #syncCollectionMembers} already does this for them. */
    private void writeVectorsForCollectionMembers(Session session, Collection<?> members) {
        for (Object member : members) {
            if (member instanceof JavAIVectorizable vectorizable
                    && member.getClass().isAnnotationPresent(Entity.class)) {
                writeVectors(session, member.getClass(), vectorizable);
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
            // ONLY JavAI collection fields belong to this side table. A plain JDK collection is mapped
            // natively by Hibernate (it isn't marked <transient>, see buildAutoTransientOverrideXml), so
            // claiming it here too would persist the same association twice and -- because
            // hydrateCollectionMembers would then add the members back onto an already-Hibernate-populated
            // collection -- silently double every element on read. Confirmed empirically before this guard
            // existed: a plain @OneToMany with 2 children reloaded as 4. See OMI-142.
            if (!isJavAICollectionField(field)) {
                continue;
            }
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
            // Mirrors syncCollectionMembers: only JavAI collection fields are hydrated from the side table.
            // Hibernate has already populated a natively-mapped collection by the time we get here, so adding
            // its members again would duplicate every element. See OMI-142.
            if (!isJavAICollectionField(field)) {
                continue;
            }
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
    private List<Object> hydrate(Class<?> entityType, List<UUID> rankedIds) {
        return inSession(session -> {
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
        });
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
                        ? nativeFactory(config.externalSessionFactory())
                        : buildSessionFactory();
                registerFlushVectorListener(sessionFactory);
                initializeSchema(sessionFactory);
            }
            return sessionFactory;
        }
    }

    /** SessionFactories this listener is already attached to. Identity-keyed and weak, because an
     *  externally-supplied factory may be shared by several backends (or outlive this one) and must never be
     *  double-registered, nor kept alive by us. */
    private static final Set<SessionFactory> FLUSH_LISTENER_REGISTERED =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** Attaches {@link JavAIFlushVectorListener} so vector writes can cover every entity Hibernate actually
     *  persists -- including ones reached only by cascading, at any depth. Works on a factory this backend
     *  built and, equally, on one the application supplied (proven in {@code PhaseZeroSpikeTest}'s Gate 2);
     *  the listener stays inert outside this backend's own save/delete calls, so a shared factory is safe. */
    private static void registerFlushVectorListener(SessionFactory factory) {
        synchronized (FLUSH_LISTENER_REGISTERED) {
            if (!FLUSH_LISTENER_REGISTERED.add(factory)) {
                return;
            }
        }
        EventListenerRegistry listeners = ((SessionFactoryImplementor) factory)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        JavAIFlushVectorListener listener = new JavAIFlushVectorListener();
        listeners.appendListeners(EventType.PRE_INSERT, listener);
        listeners.appendListeners(EventType.PRE_UPDATE, listener);
        listeners.appendListeners(EventType.POST_DELETE, listener);
    }

    /** Writes vectors for every {@code @JavAIVectorizable} Hibernate reported persisting in this flush.
     *  Complements -- never replaces -- the explicit walk above: an entity whose mapped columns didn't change
     *  produces no Hibernate event at all, which is exactly the case {@code reindexAll()} relies on. */
    private void writeVectorsForFlushedEntities(Session session) {
        for (Object entity : JavAIFlushVectorListener.current().persisted()) {
            if (entity instanceof JavAIVectorizable vectorizable
                    && entity.getClass().isAnnotationPresent(Entity.class)) {
                writeVectors(session, entity.getClass(), vectorizable);
            }
        }
    }

    /**
     * The session this call should run on when the caller already owns one, or {@code null} when this
     * backend should open (and commit) its own -- the whole of OMI-146's "join an ambient transaction"
     * behavior, in one place.
     *
     * <p>Two sources, checked in order, both scoped to <em>this</em> backend's own {@code SessionFactory} so
     * a second, independently-configured backend on the same thread is never handed the wrong session:
     * {@link JavAIPI#inTransaction} for callers who aren't running under Spring, then a Spring-managed
     * transaction ({@link SpringManagedSessions}) for callers who are.
     *
     * <p>Returning {@code null} is the ordinary case and preserves the pre-0.1.5 behavior exactly: one
     * session per repository call, committed on its own.
     */
    /**
     * The real Hibernate {@code SessionFactory} behind whatever the caller supplied. Spring's
     * {@code LocalContainerEntityManagerFactoryBean} hands out a <em>proxy</em> {@code EntityManagerFactory},
     * and {@code unwrap(SessionFactory.class)} on it yields a proxy implementing {@code SessionFactory} --
     * not the {@code SessionFactoryImplementor} this backend needs. Two things break on the proxy, both
     * discovered by test rather than reasoned about: {@link #registerFlushVectorListener} casts to
     * {@code SessionFactoryImplementor} and threw {@code ClassCastException}, and -- more subtly --
     * {@code session.getSessionFactory()} on a Spring-managed session returns the <em>native</em> factory, so
     * comparing it against the proxy never matched and no transaction was ever joined. Normalizing here, at
     * the one place an external factory enters, fixes both at once and keeps every downstream comparison
     * against a single identity.
     */
    private static SessionFactory nativeFactory(SessionFactory supplied) {
        if (supplied instanceof SessionFactoryImplementor) {
            return supplied;
        }
        return supplied.unwrap(SessionFactoryImplementor.class);
    }

    private Session ambientSession() {
        SessionFactory factory = sessionFactory();
        Session javAIScoped = JavAITransactionScope.current(factory);
        if (javAIScoped != null) {
            return javAIScoped;
        }
        return SpringManagedSessions.isAvailable() ? SpringManagedSessions.current(factory) : null;
    }

    /**
     * Runs read-only work on the ambient session if there is one, otherwise on a short-lived session of this
     * backend's own. Reads need no transaction of their own either way -- joining one matters because
     * entities the caller has already written but not yet committed must be visible to a subsequent read in
     * the same unit of work, which a separate session could not see.
     */
    private <T> T inSession(Function<Session, T> work) {
        Session ambient = ambientSession();
        if (ambient != null) {
            return work.apply(ambient);
        }
        try (Session session = sessionFactory().openSession()) {
            return work.apply(session);
        }
    }

    /**
     * Runs write work transactionally: joined to the caller's transaction when one exists -- committing and
     * rolling back with it, never independently of it -- and otherwise in this backend's own
     * open/begin/commit cycle, exactly as before OMI-146.
     *
     * <p>The joined branch deliberately neither commits nor closes: both belong to whoever opened the
     * transaction. It also doesn't roll back on failure, only propagates -- Spring marks its own transaction
     * rollback-only when the exception escapes the {@code @Transactional} boundary, and rolling back here
     * would instead end the caller's unit of work early, out from under work it still intended to do.
     */
    private <T> T inTransactionalSession(Function<Session, T> work) {
        Session ambient = ambientSession();
        if (ambient != null) {
            return work.apply(ambient);
        }
        try (Session session = sessionFactory().openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                T result = work.apply(session);
                tx.commit();
                return result;
            } catch (RuntimeException e) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                throw e;
            }
        }
    }

    /**
     * Runs {@code body} as one unit of work on one session -- {@link JavAIPI#inTransaction}'s implementation.
     * Every repository call the body makes against this backend resolves the same session via
     * {@link #ambientSession()} and therefore commits, or rolls back, exactly once, together.
     *
     * <p>Joins rather than nests when the caller is already inside a Spring transaction: that transaction is
     * the more meaningful boundary, and opening a second one underneath it would produce precisely the split
     * unit of work this method exists to prevent.
     */
    @Override
    public <T> T inTransaction(Supplier<T> body) {
        if (ambientSession() != null) {
            return body.get();
        }
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            Transaction tx = session.beginTransaction();
            JavAITransactionScope.begin(factory, session);
            try {
                T result = body.get();
                tx.commit();
                return result;
            } catch (RuntimeException e) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                throw e;
            } finally {
                JavAITransactionScope.end();
            }
        }
    }

    private SessionFactory buildSessionFactory() {
        StandardServiceRegistryBuilder registryBuilder = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.url", config.postgresUrl())
                .applySetting("jakarta.persistence.jdbc.user", config.postgresUsername())
                .applySetting("jakarta.persistence.jdbc.password", config.postgresPassword())
                .applySetting("hibernate.hbm2ddl.auto", "update");
        config.hibernateProperties().forEach(registryBuilder::applySetting);
        StandardServiceRegistry registry = registryBuilder.build();
        MetadataSources sources = new MetadataSources(registry);
        for (Class<?> entityType : registeredEntityTypes) {
            sources.addAnnotatedClass(entityType);
        }
        String autoTransientOverrideXml = buildAutoTransientOverrideXml(registeredEntityTypes);
        if (autoTransientOverrideXml != null) {
            sources.addInputStream(new ByteArrayInputStream(autoTransientOverrideXml.getBytes(StandardCharsets.UTF_8)));
        }
        MetadataBuilder metadataBuilder = sources.getMetadataBuilder();
        PhysicalNamingStrategy namingStrategy = resolvePhysicalNamingStrategy();
        if (namingStrategy != null) {
            metadataBuilder.applyPhysicalNamingStrategy(namingStrategy);
        }
        Metadata metadata = metadataBuilder.build();
        attachJavAICollectionTypes(metadata);
        return metadata.buildSessionFactory();
    }

    /**
     * The physical naming strategy to apply, or {@code null} to leave whatever the service registry already
     * resolved from settings. Three-way precedence, most specific first:
     *
     * <ol>
     *   <li>{@link JavAIPersistenceConfig.Builder#physicalNamingStrategy} -- an explicit, typed instance.</li>
     *   <li>A {@code hibernate.physical_naming_strategy} key passed through
     *       {@link JavAIPersistenceConfig.Builder#hibernateProperty} -- returns {@code null} here so
     *       Hibernate resolves that setting itself, exactly as it would in any other application.</li>
     *   <li>Neither: {@link CamelCaseToUnderscoresNamingStrategy}, so {@code emailVerified} maps to the
     *       column {@code email_verified} rather than Hibernate's bare-default {@code emailverified}. This
     *       matches Spring Boot's own default, which matters concretely: a JavAI repository pointed at a
     *       table some other tool already created under that convention now sees the same columns instead of
     *       silently adding a second, differently-cased set alongside them (OMI-145).</li>
     * </ol>
     */
    private PhysicalNamingStrategy resolvePhysicalNamingStrategy() {
        if (config.physicalNamingStrategy() != null) {
            return config.physicalNamingStrategy();
        }
        if (config.hibernateProperties().containsKey(AvailableSettings.PHYSICAL_NAMING_STRATEGY)) {
            return null;
        }
        return new CamelCaseToUnderscoresNamingStrategy();
    }

    /**
     * Makes an ordinary JPA association whose field is declared by a JavAI collection <em>interface</em>
     * (e.g. {@code @OneToMany JavAIList<Comment> comments}) use JavAI's own persistent collection, so the
     * instance Hibernate substitutes into the field keeps its vector/dirty-tracking behavior. Applied here,
     * between {@code buildMetadata()} and {@code buildSessionFactory()}, so the <b>consumer writes nothing
     * JavAI-specific</b> -- no {@code @CollectionType}, just the JPA annotation they'd write anyway.
     *
     * <p>Deliberately per-collection rather than Hibernate's {@code @CollectionTypeRegistration}, which is
     * keyed by {@code CollectionClassification} and would therefore capture <em>every</em> bag/set/map in the
     * persistence unit, including plain JDK ones that must stay exactly as Hibernate maps them.
     */
    private static void attachJavAICollectionTypes(Metadata metadata) {
        for (org.hibernate.mapping.Collection binding : metadata.getCollectionBindings()) {
            Class<?> fieldType = collectionFieldType(binding);
            if (fieldType == null) {
                continue;
            }
            if (JavAIList.class.isAssignableFrom(fieldType)) {
                binding.setTypeName(JavAIListType.class.getName());
            } else if (JavAISet.class.isAssignableFrom(fieldType)) {
                binding.setTypeName(JavAISetType.class.getName());
            } else if (JavAIMap.class.isAssignableFrom(fieldType)) {
                binding.setTypeName(JavAIMapType.class.getName());
            }
        }
    }

    /** The declared type of the field behind a collection binding, or {@code null} if it can't be resolved
     *  (an embedded/component path, or a role this backend doesn't own). */
    private static Class<?> collectionFieldType(org.hibernate.mapping.Collection binding) {
        Class<?> ownerClass = binding.getOwner() == null ? null : binding.getOwner().getMappedClass();
        if (ownerClass == null) {
            return null;
        }
        String role = binding.getRole();
        String property = role.substring(role.lastIndexOf('.') + 1);
        try {
            return EntityReflection.findField(ownerClass, property).getType();
        } catch (RuntimeException e) {
            return null; // not a plain field on the owner (component path, synthetic role, ...)
        }
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
