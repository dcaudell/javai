package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Summary;
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
import org.hibernate.Hibernate;
import org.hibernate.annotations.AnyDiscriminatorValue;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Deque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
 * {@code PersistentMap} the instant the field is persisted. A JavAI collection field must therefore be
 * declared by its <em>interface</em> ({@code JavAIList}/{@code JavAISet}/{@code JavAIMap}), non-final, and
 * carry the ordinary JPA annotation:
 *
 * <p>{@code @OneToMany private JavAIList<Comment> comments = new JavAIArrayList<>();} maps exactly like any
 * other JPA association -- a real join table with foreign keys both ways, lazy loading, {@code mappedBy},
 * cascades, {@code @ManyToMany} shared ownership. What Hibernate substitutes in is {@link PersistentJavAIList}/
 * {@link PersistentJavAISet}/{@link PersistentJavAIMap} -- JavAI's own {@code PersistentCollection}
 * implementations -- rather than {@code PersistentBag}/{@code PersistentSet}/{@code PersistentMap}, so the
 * field keeps its vector and dirty-tracking behavior across the substitution instead of losing it. The
 * consumer writes nothing JavAI-specific to get this; see {@link #attachJavAICollectionTypes} below.
 *
 * <p><b>A field declared by the concrete class is refused at registration</b> (see
 * {@link #isJavAICollectionField}). It cannot be Hibernate-managed -- the substitution fails outright with a
 * {@code ClassCastException}, confirmed empirically -- and until OMI-277 it was instead round-tripped through
 * a membership table this backend owned. That storage was only ever read and written for the entity a
 * repository call returned: reached through an association the collection came back silently empty, and
 * saved through one its members were silently never written. It could not be made lazy where it stood
 * either, since the field holds a final instance of a final class. Both the mapping and its table are gone.
 *
 * <p>So there is exactly <b>one</b> JavAI collection mapping on this backend, and
 * {@link #validateCollectionFieldMapping} rejects the concrete-typed field eagerly, with a message naming the
 * interface-typed fix. Note this is a Postgres-only distinction: the Neo4j and MongoDB backends classify
 * collections purely by declared type, have no equivalent of a native JPA association, and accept either
 * shape -- so the field that works on all three is the interface-typed one.
 *
 * <p><b>No manual {@code @Transient} required</b> on a field this backend maps itself. At
 * {@link #buildSessionFactory} time it generates an in-memory JPA {@code orm.xml}-equivalent mapping document
 * marking exactly those fields {@code <transient>} -- fed to Hibernate via
 * {@link MetadataSources#addInputStream}, alongside the ordinary {@code @Entity}-driven annotation scanning.
 * This is a real, spec-defined JPA override mechanism (XML mappings logically override annotations for
 * whatever they explicitly mention, leaving everything else annotation-driven), not a hack, and detection is
 * 100%-confidence from the field's declared type alone -- see {@link #isBackendManagedField}.
 *
 * <p>Today that means <b>{@code Point} fields only</b>, which live in {@code javai_geo_points}. It used to
 * mean JavAI collection fields as well, when they had storage of their own; since OMI-277 they are ordinary
 * Hibernate associations, and hiding them from Hibernate is the last thing wanted.
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
 * tables this backend owns itself ({@code javai_vectors__*},
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
 * <p><b>Map keys are Hibernate's business, not this backend's (OMI-277).</b> This class used to refuse any
 * {@code Map} field not keyed by {@code String}, because the membership table's key column was a plain
 * {@code varchar} and a stringified key could never round-trip back to its original type. The table is gone
 * and a map is an ordinary JPA association now, keyed however {@code @MapKeyColumn}/{@code @MapKeyEnumerated}
 * and friends say -- so the validator went with the storage it protected. {@code RepositoryBackendNeo4j} and
 * {@code RepositoryBackendSpringDataMongo} still enforce the rule, for exactly the reason it originally
 * existed: their key genuinely is a string property on a relationship or in a reference array.
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

    private static final System.Logger LOG =
            System.getLogger(RepositoryBackendHibernatePostgres.class.getName());

    private static final String FIELD_VECTOR_TABLE_PREFIX = "javai_vectors__";
    private static final String SUMMARY_VECTOR_TABLE_PREFIX = "javai_summary_vectors__";

    static {
        // Teaches Vector Core to recognise a Hibernate proxy or an uninitialized lazy collection as a
        // placeholder rather than an object, so its own graph walks look without loading (OMI-271). Done on
        // class load rather than per configuration: it is a property of "Hibernate is what's underneath",
        // which is true for every instance of this class and cannot differ between two of them. Harmless to
        // the other two backends -- Hibernate.isInitialized answers true for anything it does not manage.
        JavAIRuntime.configureInitializationCheck(Hibernate::isInitialized);
    }

    /** How many queued owners one maintenance drain claims at a time. Bounded so a large backlog is worked
     *  through in several short transactions rather than one long one holding advisory locks throughout. */
    private static final int DRAIN_BATCH_SIZE = 256;

    /** How many times a drain re-runs after a conflict with another pod's drain before giving up and leaving
     *  the work queued. Small on purpose: the advisory lock already makes a second collision unlikely, and
     *  the queue means giving up costs a delay, not the recomputation. */
    private static final int DRAIN_MAX_ATTEMPTS = 3;

    private final JavAIPersistenceConfig config;
    private final Set<Class<?>> registeredEntityTypes = ConcurrentHashMap.newKeySet();

    /** Vector tables this backend instance has already provisioned out of band -- see
     *  {@link #ensureFieldVectorTable} for why this may be cached now when it could not be before.
     *  Instance-scoped, per this repository's coding standard: two configs against two databases must not
     *  share one another's belief about what exists. */
    private final Map<String, Boolean> provisionedTables = new ConcurrentHashMap<>();

    /** The declared {@code @Summary} containment of the registered model -- see {@link #containment()} for
     *  why it is resolved lazily rather than in the constructor. */
    private volatile Containment containment;

    /** Where the call that built {@link #sessionFactory} came from -- see {@link #describeCallingSite}. */
    private volatile String factoryBuildTrigger;
    private final Object bootstrapLock = new Object();
    private volatile SessionFactory sessionFactory;

    RepositoryBackendHibernatePostgres(JavAIPersistenceConfig config) {
        this.config = config;
        // Types the caller named explicitly, plus every @Entity under any package they asked us to scan.
        // Registered up front so the entity set is complete before anything can be built -- which is what
        // removes registration ordering as a concern for the caller (OMI-214).
        for (Class<?> scanned : EntityPackageScanner.scan(
                config.entityPackages(), Thread.currentThread().getContextClassLoader() != null
                        ? Thread.currentThread().getContextClassLoader()
                        : getClass().getClassLoader(),
                config.excludedEntityTypes(), config.excludedEntityPackages())) {
            try {
                registerEntityType(scanned);
            } catch (RuntimeException e) {
                // Scanned types are validated exactly like named ones -- an entity JavAI cannot map
                // correctly must not be registered silently, since Hibernate will map it anyway and JavAI's
                // half will be wrong. But the caller never asked for this type by name, so say where it
                // came from and how to stop pulling it in; otherwise the message reads as an error about
                // an unrelated class.
                throw new IllegalArgumentException("Scanning " + config.entityPackages() + " for @Entity types "
                        + "found " + scanned.getName() + ", which JavAI cannot map.\n"
                        + "  Underlying problem: " + e.getMessage() + "\n"
                        + "Note this is never about an entity being non-vectorized: a plain @Entity with no "
                        + "@JavAIVectorizable registers and maps exactly like a vectorized one, it just has "
                        + "no vectors. Only three things are refused -- a JavAI collection field keyed by "
                        + "something other than String, a KnowledgeGraph field (Neo4j-only), and a "
                        + "collection field that is either unmapped or a concrete-typed JavAI collection "
                        + "carrying an association annotation.\n"
                        + "If this type belongs to a different persistence unit, exclude it: "
                        + "excludeEntityType(" + scanned.getSimpleName() + ".class), "
                        + "excludeEntityPackages(\"" + scanned.getPackageName() + "\"), or annotate it "
                        + "@PersistenceIgnore. Otherwise fix the mapping.", e);
            }
        }
        for (Class<?> additional : config.additionalEntityTypes()) {
            registerEntityType(additional);
        }
    }

    @Override
    public void registerEntityType(Class<?> entityType) {
        Set<Class<?>> closure = entityClosure(entityType);
        if (sessionFactory != null) {
            // The window is shut, but that only matters if this call would introduce something Hibernate
            // has never seen. Re-realizing a repository for an already-known type -- or for one pulled in
            // by another entity's fields, or named on the config -- asks nothing new of the frozen
            // metadata, so it is simply a no-op (OMI-214). Before this, ANY registration after the first
            // repository call threw, which is what made declaring types up front unable to solve the
            // ordering problem: the late call failed however completely the types had been declared.
            List<Class<?>> unknown = closure.stream()
                    .filter(type -> !registeredEntityTypes.contains(type))
                    .toList();
            if (unknown.isEmpty()) {
                return;
            }
            throw new IllegalStateException(lateRegistrationMessage(unknown));
        }
        for (Class<?> type : closure) {
            validateNoKnowledgeGraphFields(type);
            validateCollectionFieldMapping(type);
            registeredEntityTypes.add(type);
        }
    }

    /**
     * Explains a registration that genuinely cannot be honoured, naming <b>what closed the window</b>.
     *
     * <p>The old message named only the type that arrived late, which is never the thing a consumer has to
     * change -- the fix is always to move whatever built the factory, or to stop needing the ordering at
     * all. Downstream that cost real time twice: {@code omiai-platform} maintains an 18-name
     * {@code @DependsOn} list purely to control this, and OMI-212's boot failure read as a mapping bug for
     * the same reason. So this reports the triggering call site and points at the way out (OMI-214).
     */
    private String lateRegistrationMessage(List<Class<?>> unknown) {
        String names = unknown.stream().map(Class::getName).collect(Collectors.joining(", "));
        return "Cannot register " + names + " -- the SessionFactory is already built, so Hibernate's mapping "
                + "metadata is frozen and " + (unknown.size() == 1 ? "this type" : "these types")
                + " would never be mapped.\n"
                + "  Built by: " + (factoryBuildTrigger == null ? "(unknown)" : factoryBuildTrigger) + "\n"
                + "That call is what closed the registration window; every JavAIPI.repository(...) has to "
                + "happen before it. Better still, stop depending on the ordering altogether by naming the "
                + "types on the configuration, which registers them before anything can be built:\n"
                + "  JavAIPersistenceConfig.builder().entityType(" + unknown.get(0).getSimpleName()
                + ".class)  -- or .entityTypes(...) / .entityPackages(\"your.domain.package\")";
    }

    /**
     * Every entity type registering {@code root} would pull in: itself, plus everything reachable through
     * its fields, transitively. Pure -- it registers nothing and validates nothing -- because
     * {@link #registerEntityType} needs to know what a registration <em>would</em> add before deciding
     * whether it may proceed.
     */
    private static Set<Class<?>> entityClosure(Class<?> root) {
        Set<Class<?>> closure = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Class<?> type = pending.poll();
            if (!closure.add(type)) {
                continue; // already seen -- also the cycle guard, for two entities referencing each other
            }
            for (Field field : EntityReflection.allFields(type)) {
                pending.addAll(relatedEntityTypes(field));
            }
        }
        return closure;
    }


    /**
     * Every {@code @Entity}-annotated type reachable through {@code field}: its own type for a singular
     * reference, its generic element type (collection) or value type (map), and -- for an {@code @Any}
     * association -- each concrete target named by {@code @AnyDiscriminatorValue}. Empty for anything else
     * (scalar fields, non-entity related types, unresolvable generics).
     *
     * <p>Returns a collection rather than a single type because of {@code @Any} (OMI-212): one field can
     * name several unrelated targets, and they are reachable <em>only</em> through the discriminator. An
     * {@code @Any} field's declared type is deliberately a plain interface with no shared table -- that is
     * the whole point of the mapping -- so walking the declared type learns nothing, the targets went
     * unregistered, and the {@code SessionFactory} failed to boot with {@code UnknownEntityTypeException}.
     * That failure is at boot, so it took out every repository call in the configuration, not merely ones
     * touching the association.
     *
     * <p>Nothing about the mapping itself was ever missing: JavAI registers entities through ordinary
     * Hibernate annotation scanning, so once the targets are known, {@code @Any} works. The gap was purely
     * discovery.
     */
    private static List<Class<?>> relatedEntityTypes(Field field) {
        List<Class<?>> related = new ArrayList<>(2);

        Class<?> fieldType = field.getType();
        if (fieldType.isAnnotationPresent(Entity.class)) {
            related.add(fieldType);
        } else if (Map.class.isAssignableFrom(fieldType)) {
            Class<?> valueType = genericTypeArgument(field, 1);
            if (valueType != null && valueType.isAnnotationPresent(Entity.class)) {
                related.add(valueType);
            }
        } else if (Collection.class.isAssignableFrom(fieldType)) {
            Class<?> elementType = genericTypeArgument(field, 0);
            if (elementType != null && elementType.isAnnotationPresent(Entity.class)) {
                related.add(elementType);
            }
        }

        // getAnnotationsByType unwraps the repeatable container (@AnyDiscriminatorValues) as well as the
        // single form, so both spellings are covered without handling the container explicitly.
        for (AnyDiscriminatorValue discriminatorValue : field.getAnnotationsByType(AnyDiscriminatorValue.class)) {
            Class<?> target = discriminatorValue.entity();
            if (target.isAnnotationPresent(Entity.class) && !related.contains(target)) {
                related.add(target);
            }
        }
        return related;
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
    /** The interface a consumer should declare instead of {@code type}, for the refusal message above. */
    private static String javAIInterfaceFor(Class<?> type) {
        if (Map.class.isAssignableFrom(type)) {
            return "JavAIMap";
        }
        return Set.class.isAssignableFrom(type) ? "JavAISet" : "JavAIList";
    }

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
     * concrete-typed JavAI collection is refused outright (OMI-277), so
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
            // @ManyToAny is the polymorphic to-many, and is every bit as much a mapping annotation as
            // @OneToMany/@ManyToMany -- it just carries its target types on a discriminator rather than in
            // the field's generic argument. Omitting it here rejected a correctly-mapped field with advice
            // to add an annotation the user had effectively already added (OMI-212).
            boolean association = field.isAnnotationPresent(OneToMany.class)
                    || field.isAnnotationPresent(ManyToMany.class)
                    || field.isAnnotationPresent(org.hibernate.annotations.ManyToAny.class);
            if (isJavAICollectionField(field)) {
                throw new IllegalArgumentException("Cannot map " + entityType.getName() + "."
                        + field.getName() + ": the field is declared as the CONCRETE type "
                        + field.getType().getSimpleName() + ", which Hibernate can never manage -- it "
                        + "substitutes its own collection instance into the field, and a final class cannot "
                        + "be substituted. Declare it by the JavAI INTERFACE instead -- 'private "
                        + javAIInterfaceFor(type) + "<X> " + field.getName() + " = new "
                        + field.getType().getSimpleName() + "<>();', non-final, with the ordinary JPA "
                        + "annotation (@OneToMany/@ManyToMany, plus @MapKeyColumn for a map) -- and the "
                        + "association becomes a native Hibernate one, with vectors and dirty-tracking "
                        + "preserved. This shape used to be accepted and stored out-of-band in "
                        + "an out-of-band membership table; it is refused as of OMI-277, and that table is "
                        + "gone, because the storage was only ever read and written for the entity a "
                        + "repository call returned. Reached through an association the collection came back "
                        + "silently empty, and saved through one its members were silently never written.");
            }
            if (!association && !field.isAnnotationPresent(ElementCollection.class)
                    && !field.isAnnotationPresent(Transient.class)) {
                throw new IllegalArgumentException("Postgres persistence cannot map the collection field "
                        + entityType.getName() + "." + field.getName() + " -- a plain JDK collection needs a JPA "
                        + "mapping annotation (@OneToMany/@ManyToMany for entities, @ManyToAny for a polymorphic "
                        + "collection, @ElementCollection for "
                        + "basic/embeddable values), or @Transient to exclude it. For a vector-aware, "
                        + "dirty-tracking collection, declare the field by a JavAI INTERFACE "
                        + "(JavAIList/JavAISet/JavAIMap), non-final, and annotate it exactly the same way -- "
                        + "it stays an ordinary JPA association, and JavAI's own collection instance is "
                        + "preserved across Hibernate's substitution.");
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
     *  Without this their side-table rows would outlive the rows they describe. (There used to be a second
     *  path here for members held in a membership table of this backend's own; it went with the table in
     *  OMI-277, and a natively-mapped association is now the only shape there is.) */
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

    /** A field this backend maps itself rather than letting Hibernate map it: a geo {@code Point}, through
     *  {@code javai_geo_points} + earthdistance, marked {@code <transient>} in the generated override mapping
     *  so Hibernate's boot-time mapping doesn't choke on a type it cannot map.
     *
     *  <p>JavAI collections used to be the other half of this. They are native Hibernate associations now
     *  (OMI-277), so they are mapped rather than hidden. */
    private static boolean isBackendManagedField(Field field) {
        return Point.class.isAssignableFrom(field.getType());
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
        return save(entityType, entity, SummaryPolicy.RECOMPUTE_AFTER_COMMIT);
    }

    @Override
    public Object save(Class<?> entityType, Object entity, SummaryPolicy policy) {
        // The whole reachable subgraph is locked (and every field/summary vector forced accurate) for the
        // duration of the flush below -- this is what guarantees writeVectors()/writeVectorsForRelatedEntities()
        // below can never persist a vector that's stale relative to the field value committed in this same
        // transaction, regardless of the ambient EmbeddingConsistencyMode.
        Object[] result = new Object[1];
        Set<Containment.OwnerRef> touched = new LinkedHashSet<>();
        // Load-time hydration is suspended for this whole unit of work: merge() loads the row before it
        // copies the caller's values on, so hydrating there would pair the old vector with the new value
        // (see JavAIPostLoadVectorListener). This method does its own, below, where the caller's instance is
        // still distinguishable from the merged copy.
        boolean hydrationWasSuspended = JavAIPostLoadVectorListener.suspend();
        try {
        JavAIRuntime.runWithSubgraphLockedForPersistence(entity, () -> result[0] = inTransactionalSession(session -> {
            JavAIFlushVectorListener.begin();
            try {
                // Assigned before merge(), recursively: a cascaded @OneToOne (or a @Transient collection
                // element this backend persists itself) needs its own id set before Hibernate/this backend
                // tries to INSERT it -- there's no @GeneratedValue, identity is always application-assigned.
                ensureIdsAssigned(entity, new IdentityHashMap<>());
                Object managed = session.merge(entity);
                // merge() copies mapped field values onto its managed copy but not the woven $javai$state
                // holding this project's vector caches -- the same "transient state stays on the original"
                // property syncGeoPoints below already relies on. Without this, the
                // managed copy looks brand new and re-embeds vectors the caller already had, which is the
                // bulk of what OMI-187 measured. Only clean, already-computed slots move across, so a field
                // the caller actually changed is still embedded fresh.
                Map<UUID, Object> originalsById = vectorizablesById(entity);
                transferVectorState(entity, managed, originalsById);
                transferToSummaryChildren(managed, originalsById);
                // A @Summary child the caller never held arrives from the database with empty cache slots,
                // and computing this container's summary vector below reads every one of them -- so without
                // this it is re-embedded for real, once per child, per save (OMI-271). The load path used to
                // mask this by hydrating the whole reachable graph on every read; it no longer does, so the
                // cost is paid here, where it is one SELECT per summary child of one container, rather than
                // there, where it was one per entity reachable from anything anyone read.
                hydrateSummaryChildren(session, managed,
                        Collections.newSetFromMap(new IdentityHashMap<>()), originalsById);
                session.flush();
                writeVectors(session, entityType, managed);
                writeVectorsForRelatedEntities(session, managed, originalsById);
                // Reads from the original `entity`, not `managed`: a Point field is @Transient (mapped by
                // this backend, not Hibernate), so merge() never copies it onto the managed instance and its
                // value lives only on the original. entity's id was already assigned above, so it matches.
                syncGeoPoints(session, entity, new IdentityHashMap<>());
                // Last, after every write above has been flushed: covers anything Hibernate persisted by
                // cascading that the explicit walks never reach (a related entity two or more hops away).
                // Flushed, not committed: when this call joined a caller's transaction (OMI-146) the commit
                // is theirs to make, and these vector rows must land or roll back with everything else they
                // did -- which they do, since this is the caller's own session and connection.
                session.flush();
                writeVectorsForFlushedEntities(session, originalsById);
                // And back the other way, now that everything above has computed real vectors on the
                // managed copies. This half is what actually pays off across repeated saves: writeVectors
                // computes on `managed`, but save() hands the caller `entity` back, so without this the
                // caller's own instance stays cold forever and every subsequent save re-embeds it from
                // scratch. That is exactly the shape of a seeding loop -- save a TagSet, then save N tags
                // that each reference the caller's TagSet -- and it was the last of OMI-187's waste.
                transferVectorState(managed, entity, vectorizablesById(managed));
                // And the @Version Hibernate just bumped, for the same reason and at the same moment: the
                // caller keeps `entity`, so anything the write assigned has to be carried back to it or the
                // instance they hold is already stale the moment save() returns (OMI-254).
                refreshVersions(entity, managed);
                // Records, inside this same transaction, which containers now owe a summary recomputation --
                // an insert per owner, which cannot collide with a concurrent writer's inserts the way the
                // shared summary row it replaces did. Rolls back with everything else if this save fails,
                // so a recomputation is never queued for a mutation that never happened (OMI-255).
                touched.addAll(participatingOwners(entity, managed));
                enqueueSummaries(session, touched);
                // The @Transient Point fields, which merge() does not copy because this backend maps them
                // itself. They have just been written to javai_geo_points from `entity`; putting them on
                // `managed` too is what makes it safe to return (OMI-275).
                copyBackendManagedFields(entity, managed, Collections.newSetFromMap(new IdentityHashMap<>()));
                // Returns the MANAGED instance, as Spring Data JPA's save() does (OMI-275, Topic 1).
                //
                // It used to return the caller's own `entity`, because merge() left @Transient JavAI
                // collection fields empty on the managed copy and returning it would have handed back an
                // object whose collections looked wrong immediately after a save. OMI-277 removed that
                // reason: a JavAI collection is a native Hibernate association now, so merge() carries it
                // across, and only Point fields are still @Transient -- copied explicitly just above.
                //
                // What this fixes is not merely a difference from Spring Data. Returning an unmanaged root
                // while the session was open was the ONE way a caller could be handed a graph that is
                // attached in one place and detached in another: mutate the root and the change is silently
                // discarded, mutate a child reached through it and the change is silently persisted, with
                // nothing about either object saying which is which. See
                // AttachmentConformanceTest.aSavedRootHoldingAPreviouslyLoadedChildIsAMixedGraph, which
                // measured exactly that before this line changed.
                return managed;
            } finally {
                JavAIFlushVectorListener.end();
            }
        }));
        } finally {
            // Restored before the drain below, deliberately: that runs after this transaction commits, loads
            // the container and its children fresh, and wants them hydrated exactly as any other read does.
            JavAIPostLoadVectorListener.restore(hydrationWasSuspended);
        }
        if (policy == SummaryPolicy.RECOMPUTE_AFTER_COMMIT && !touched.isEmpty()) {
            recomputeAfterCommit(touched);
        }
        return result[0];
    }

    /**
     * The batch as one transaction, with every embedding computed before it opens (OMI-266).
     *
     * <p>Overridden purely to add the atomicity {@link JavAIPI#inTransaction} provides -- either every entity
     * in the batch is saved or none is, which is the guarantee a caller reaching for a bulk write on a
     * relational store expects. The batching itself is the SPI default's and is identical on all three
     * backends.
     *
     * <p>The warm is deliberately outside {@code inTransaction}: those are network round trips to an
     * embedding provider, and holding a database transaction open across them is exactly the coupling this
     * ticket set out to shorten. Anything mutated in between simply leaves its slot dirty for that entity's
     * own locked, accuracy-forced save to recompute, so moving it out costs no correctness.
     */
    @Override
    public List<Object> saveAll(Class<?> entityType, List<Object> entities, SummaryPolicy summaryPolicy) {
        JavAIRuntime.warmSubgraphsForPersistence(entities);
        return inTransaction(() -> {
            List<Object> saved = new ArrayList<>(entities.size());
            for (Object entity : entities) {
                saved.add(save(entityType, entity, summaryPolicy));
            }
            return saved;
        });
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

        // QUEUE_ONLY throughout, then one drain at the end (OMI-255). Recomputing after each save would open
        // a transaction per entity for a value that is about to be superseded by the next entity's save
        // anyway -- a re-index rewrites the whole store, so every container is going to be recomputed
        // regardless of how many times it is asked for along the way. The queue collapses those requests.
        // Chunked and batch-warmed (OMI-266). A re-index is the largest bulk embedding this library performs
        // -- by construction every entity must be re-embedded and no stored vector is reusable -- so it is
        // the flow with the most round trips to save and the least to lose: there is no waste to remove here,
        // only sequential calls to collapse.
        for (Class<?> registered : registeredEntityTypes) {
            reindexInChunks(registered, SummaryPolicy.QUEUE_ONLY);
        }
        drainPendingSummaries();

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
            // No post-load step of its own: JavAIPostLoadVectorListener served this entity -- and every
            // other one Hibernate materialized, whenever it did so -- as it was loaded (OMI-276/277).
            return Optional.ofNullable(session.find(entityType, id));
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
            return (List<Object>) (List<?>) session.createQuery(query).list();
        });
    }

    @Override
    public void deleteById(Class<?> entityType, UUID id) {
        Set<Containment.OwnerRef> containers = new LinkedHashSet<>();
        inTransactionalSession(session -> {
            JavAIFlushVectorListener.begin();
            try {
                // Resolved BEFORE the removal, necessarily: afterwards the join rows that name these
                // containers are gone, and there is no way left to discover who used to hold this entity.
                // Without this a container goes on summarising something it no longer holds -- the exact
                // drift OMI-255 describes, arriving by deletion rather than by concurrency, and just as
                // silent (nothing fails; similarity search simply answers with a stale shape).
                containers.addAll(containment().containersOf(session, entityType, id));
                // And detached from every container holding it, @Summary or not, before the row goes.
                // A membership that outlives its member is a dangling reference at best and, on a natively
                // mapped association, a foreign key the database simply refuses -- so whether deleteById
                // worked at all used to depend on which storage shape the container happened to use.
                detachFromContainers(session, entityType, id);
                Object entity = session.find(entityType, id);
                if (entity != null) {
                    // A cascaded member's own vector/geo rows need clearing before Hibernate cascades the
                    // removal itself -- nothing else is tracking them.
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
                enqueueSummaries(session, containers);
                return null;
            } finally {
                JavAIFlushVectorListener.end();
            }
        });
        if (!containers.isEmpty()) {
            recomputeAfterCommit(containers);
        }
    }

    /**
     * Ranks by similarity, narrowed by an optional relational predicate that is applied <b>before</b> the
     * limit (OMI-230).
     *
     * <p>That ordering is the entire point, and it is why this resolves the predicate to an id set first
     * rather than filtering the ranked output. Ranking then filtering answers a different question -- "which
     * of the nearest N happen to match" -- and gives back fewer than N whenever the predicate is selective,
     * which is precisely the over-fetch-and-discard the caller was trying to escape. Resolving first means
     * {@code LIMIT} sees only rows that already match, so N nearest matches means N.
     *
     * <p>The id set is resolved through the ordinary derived-finder Criteria machinery
     * ({@link #buildWhere}), not a second predicate translator written for vectors: a predicate expressible
     * in a {@code findBy…} finder is expressible here, with identical semantics, because it is literally the
     * same code. The cost is materializing the matching ids -- bounded by how selective the predicate is,
     * not by the store -- which is the trade this makes deliberately: one id array beats an unbounded number
     * of round trips, and beats a join this backend would otherwise have to synthesize against a table name
     * it only knows through Hibernate's mapping.
     */
    @Override
    public List<Ranked<Object>> findNearest(Class<?> entityType, NearestSpec spec) {
        EmbeddingVector reference = spec.reference();
        boolean entityGrain = spec.kind() == DerivedQueryMethods.Kind.SUMMARY
                || spec.kind() == DerivedQueryMethods.Kind.CONCATENATED_TEXT;
        String table = entityGrain
                ? ensureSummaryVectorTable(reference.modelId(), reference.dims())
                : ensureFieldVectorTable(reference.modelId(), reference.dims());
        String vectorColumn = spec.kind() == DerivedQueryMethods.Kind.CONCATENATED_TEXT
                ? "concatenated_text_vector" : "vector";
        String fieldName = entityGrain ? null : spec.fieldName();

        List<UUID> allowedIds = null;
        if (spec.isNarrowed()) {
            allowedIds = matchingIds(entityType, spec.predicate());
            if (allowedIds.isEmpty()) {
                return List.of(); // nothing satisfies the predicate, so nothing can be near and satisfy it
            }
        }
        List<UUID> allowed = allowedIds;
        List<RankedId> ranked = inSession(session -> session.doReturningWork(connection -> rankIds(connection,
                table, entityType, fieldName, reference, spec.limitIncludingOffset(), vectorColumn, allowed)));
        if (spec.offset() > 0) {
            ranked = ranked.size() <= spec.offset()
                    ? List.of() : new ArrayList<>(ranked.subList(spec.offset(), ranked.size()));
        }
        return hydrateRanked(entityType, ranked);
    }

    /**
     * The ids satisfying {@code orGroups}, as an ordinary Criteria query over the entity's own table.
     *
     * <p>{@code distinct} unconditionally: a predicate reaching through a to-many association yields one row
     * per matching member, and an id repeated in the array would rank the same entity several times.
     */
    private List<UUID> matchingIds(Class<?> entityType, List<List<DerivedFinderQuery.BoundPart>> orGroups) {
        String idField = EntityReflection.idField(entityType).getName();
        return inSession(session -> {
            HibernateCriteriaBuilder cb = session.getCriteriaBuilder();
            JpaCriteriaQuery<UUID> cq = cb.createQuery(UUID.class);
            JpaRoot<?> root = cq.from(entityType);
            cq.select(root.get(idField));
            cq.distinct(true);
            Predicate where = buildWhere(session, cb, root, entityType, orGroups);
            if (where != null) {
                cq.where(where);
            }
            return session.createQuery(cq).list();
        });
    }

    // ---- ordinary derived finders (OMI-138): JPA Criteria translation --------------------------

    /** Rejects, at repository-creation time, a derived finder this backend can't translate. Nested filter
     *  paths traverse both singular {@code @Entity} associations (Criteria joins) and to-many/JavAI-collection
     *  fields (resolved to an id set). Leaf rules depend on the
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
                if (Point.class.isAssignableFrom(field.getType())) {
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
            } else if (Point.class.isAssignableFrom(field.getType())) {
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
        // leave orphaned javai_vectors__* rows behind.
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
     *  included). Anything needing a side table -- geo
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

        // Geo lives in javai_geo_points, which Hibernate cannot join, so it still resolves to an id set.
        // Everything else is a real Criteria query: every collection is a genuine association now, so a join
        // is both correct and a single statement (OMI-142 Phase 3; the side-table shape that used to share
        // this branch went with OMI-277).
        if (geo) {
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
     *  to-many hop via an HQL join over the association).
     *
     *  <p>Only geo reaches this now. Every other predicate is a Criteria query against a real association --
     *  the side-table shape that needed id-set-per-hop resolution went with OMI-277 -- but geo lives in
     *  {@code javai_geo_points}, which Hibernate cannot join, so the walk survives for it. */
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
                    ? toManyParentIds(session, parentType, segments[i], ownerTypes[i + 1], ids)
                    : singularParentIds(session, parentType, segments[i], ownerTypes[i + 1], ids);
        }
        return ids;
    }

    private Set<UUID> leafOwnerIds(
            Session session, Class<?> type, String field, DerivedFinderQuery.BoundPart part) {
        return switch (part.type()) {
            case NEAR, WITHIN -> geoOwnerIds(session, type, field, DerivedFinderQuery.geoCircle(part));
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



    /** The owners holding any of {@code memberIds} through a to-many association, by HQL join -- the native
     *  equivalent of the membership-table lookup this replaced (OMI-277). */
    private Set<UUID> toManyParentIds(Session session, Class<?> ownerType, String field,
            Class<?> memberType, Set<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        String ownerId = EntityReflection.idField(ownerType).getName();
        String memberId = EntityReflection.idField(memberType).getName();
        return new LinkedHashSet<>(session.createQuery(
                        "select distinct p." + ownerId + " from " + ownerType.getName() + " p"
                                + " join p." + field + " c where c." + memberId + " in (:memberIds)", UUID.class)
                .setParameterList("memberIds", memberIds)
                .getResultList());
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
     *  {@code javai_geo_points}. Point fields are {@code @Transient} (see {@link #isBackendManagedField}), so
     *  {@code merge()} does not carry them onto the managed copy and their value lives only on the caller's
     *  original object -- which is why {@link #save} passes {@code entity} here, not the merged instance, and
     *  copies them across explicitly afterwards. They are the last field kind this is true of. */
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

    /**
     * The singular related entities, collection elements, and map values reachable through {@code entity}'s
     * own fields -- the graph {@link #syncGeoPoints} recurses over.
     *
     * <p><b>An uninitialized association is skipped, never resolved</b> (OMI-271). Without the
     * {@code Hibernate.isInitialized} guard this walk enforced laziness on singular associations only, by
     * accident rather than by design: an uninitialized singular proxy is a generated subclass, and
     * {@code @Entity} is not {@code @Inherited}, so the {@code isAnnotationPresent} test below happens to
     * reject it. A lazy {@code @OneToMany}/{@code @ManyToMany} has no such accident protecting it --
     * {@code addAll} iterates the {@code PersistentCollection}, and iterating one <em>is</em> initializing
     * it. So every read of any entity loaded its whole reachable collection graph, recursively, and paid a
     * side-table SELECT per entity in it, no matter what the caller had asked for.
     *
     * <p>That is the invariant {@code savingDoesNotForceUninitializedLazyAssociationsToLoad} states and
     * {@code versionedEntitiesById} already keeps for its own walk, for the same reason and in the same
     * words -- this walk simply never had it. Applying it here covers the read path
     * ({@link #hydrateLoaded}) and the write path ({@link #syncGeoPoints}) at once: an association the
     * caller never touched holds nothing this backend needs to read back or write out.
     */
    /**
     * Copies the fields this backend maps itself -- {@code Point}s -- from the caller's instance onto the
     * managed copy, over the same graph {@link #syncGeoPoints} just wrote to the database.
     *
     * <p>Needed because those fields are {@code <transient>} to Hibernate, so {@code merge()} does not carry
     * them: without this, {@code save()} would return an entity whose {@code Point} reads {@code null}
     * immediately after being set, which is the class of "looks wrong right after a save" problem that kept
     * this method returning the caller's own instance for so long (OMI-275).
     */
    private static void copyBackendManagedFields(Object from, Object to, Set<Object> visited) {
        if (from == null || to == null || from == to || !visited.add(from)) {
            return;
        }
        if (!from.getClass().isInstance(to)) {
            return; // a proxy or subclass mismatch: nothing sensible to copy field-for-field
        }
        for (Field field : EntityReflection.allFields(from.getClass())) {
            if (!Point.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            try {
                field.set(to, field.get(from));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot copy backend-managed field " + field, e);
            }
        }
    }

    private static List<Object> reachableRelated(Object entity) {
        List<Object> related = new ArrayList<>();
        // A JDK value is a leaf. Reflecting into one is not merely pointless, it throws: an
        // @ElementCollection of Strings puts this walk on String.value and the module system refuses to open
        // java.lang for it (OMI-275). The guard belongs here rather than at each call site, because every
        // caller iterates whatever this returns and would need it independently.
        if (entity.getClass().getName().startsWith("java.")) {
            return related;
        }
        for (Field field : EntityReflection.allFields(entity.getClass())) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + entity.getClass(), e);
            }
            if (!Hibernate.isInitialized(value)) {
                continue; // true for null and for anything Hibernate is not managing lazily
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

        // NOT hydrated here, deliberately -- see hydrateVectors' javadoc. Hydrating on the write path
        // persisted a stale vector, caught by savedVectorIsAlwaysAccurateUnderImmediateConsistency.
        //
        // An absent vector (EmbeddingVector.absent(), OMI-187) means "no embeddable content". It is not
        // written -- zero dimensions would try to provision a vector(0) column under the synthetic
        // "<absent>" model id -- and, just as importantly, any row a previous save left for that field is
        // deleted. Skipping the write alone would leave a stale vector behind, and a stale row in an ANN
        // index is worse than a wasted embedding: the entity keeps matching searches for content it no
        // longer has. Reaching it needs a @Vectorize field to go from populated to null between saves.
        //
        // The write is planned first and issued second, so that every table this save needs is provisioned
        // before the caller's connection is touched at all. Provisioning inside the doWork below would run
        // DDL while this transaction already holds row locks on the very table being provisioned, which is
        // how two concurrent saves deadlocked on each other (OMI-255 -- see ensureFieldVectorTable).
        String currentModelId = JavAIRuntime.currentModelId();
        Map<String, EmbeddingVector> toWrite = new LinkedHashMap<>();
        List<String> toDelete = new ArrayList<>();
        for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
            EmbeddingVector vector = vectorizable.fieldVector(fieldName);
            if (vector.isAbsent()) {
                toDelete.add(fieldName);
            } else {
                toWrite.put(fieldName, vector);
                ensureFieldVectorTable(vector.modelId(), vector.dims());
            }
        }
        EmbeddingVector combined = vectorizable.vector();
        if (combined.isAbsent()) {
            toDelete.add(COMBINED_VECTOR_FIELD);
        } else {
            toWrite.put(COMBINED_VECTOR_FIELD, combined);
            ensureFieldVectorTable(combined.modelId(), combined.dims());
        }

        session.doWork(connection -> {
            // What is already stored, read once for the whole entity. Skipping a write whose value is
            // unchanged is not a micro-optimisation: at REPEATABLE READ a value-identical UPDATE still
            // creates a row version, and that version is exactly what a concurrent writer collides with.
            // Two people adding two different things to one container were refusing each other over rows
            // neither of them had changed.
            Map<String, StoredVector> stored = readStoredFieldVectors(connection, ownerType, id, currentModelId);
            for (Map.Entry<String, EmbeddingVector> entry : toWrite.entrySet()) {
                EmbeddingVector vector = entry.getValue();
                if (isUnchanged(stored.get(entry.getKey()), vector)) {
                    continue;
                }
                String table = FIELD_VECTOR_TABLE_PREFIX + ModelIds.sanitize(vector.modelId());
                upsertVector(connection, table, ownerType, id, entry.getKey(), vector);
            }
            for (String fieldName : toDelete) {
                deleteFieldVectorRow(connection, currentModelId, ownerType, id, fieldName);
            }
        });

        // The entity-grain row (summary vector + concatenated text) is deliberately NOT written here when
        // this entity is a @Summary container -- see writeEntityGrainVectors, and OMI-255's own section in
        // doc/ai-guidance/persistence-support-matrix.md for the contract that follows from it.
        if (!isSummaryContainer(entityType)) {
            writeEntityGrainVectors(session, vectorizable, ownerType, id);
        }
    }

    /**
     * Writes {@code javai_summary_vectors__<model>}'s single row for one owner: the summary vector, plus the
     * concatenated text and its vector when the entity participates (OMI-191).
     *
     * <p><b>Who calls this, and when, is the whole of OMI-255.</b> For an entity that declares no
     * {@code @Summary} field, this runs inline in the caller's transaction exactly as it always has -- such a
     * row changes only when that entity itself changes, so the only writer that can collide with it is
     * another write to the same entity, which is a genuine conflict and should be refused.
     *
     * <p>For a {@code @Summary} <em>container</em> it runs from the drain instead, after the caller has
     * committed. That row is shared by every mutation anywhere beneath the container, so leaving it here made
     * two unrelated writers collide on it -- and no lock taken inside the writer's transaction can fix that
     * at {@code REPEATABLE READ}, because the snapshot is already fixed by the time the container is known.
     *
     * <p>The write itself is unchanged: write when <em>either</em> value is present, delete only when both
     * are absent, and always assign the concatenated columns rather than leaving them alone, so switching
     * {@code @Summary(concatenate = true)} off clears a previously-stored text vector instead of leaving it
     * to keep matching searches.
     */
    private void writeEntityGrainVectors(Session session, JavAIVectorizable vectorizable,
            String ownerType, UUID id) {
        String currentModelId = JavAIRuntime.currentModelId();
        EmbeddingVector summary = vectorizable.summaryVector();
        EmbeddingVector concatenated = vectorizable.concatenatedTextVector();
        // Provisioned before the connection is borrowed, not inside doWork: provisioning opens a session of
        // its own, and taking a second connection while holding one is a pool-exhaustion risk under load as
        // well as the lock-ordering hazard ensureFieldVectorTable documents.
        String summaryTable = summary.isAbsent() ? null
                : ensureSummaryVectorTable(summary.modelId(), summary.dims());
        session.doWork(connection ->
                writeEntityGrainRow(connection, summaryTable, vectorizable, ownerType, id,
                        currentModelId, summary, concatenated));
    }

    private static void writeEntityGrainRow(Connection connection, String summaryTable,
            JavAIVectorizable vectorizable, String ownerType, UUID id, String currentModelId,
            EmbeddingVector summary, EmbeddingVector concatenated) throws SQLException {
        if (summary.isAbsent() && concatenated.isAbsent()) {
            deleteSummaryVectorRow(connection, currentModelId, ownerType, id);
            return;
        }
        if (summary.isAbsent()) {
            // The `vector` column is NOT NULL, so a row cannot hold concatenated text without a summary
            // vector. Reasoning says the two always co-occur -- text implies content, and content implies a
            // non-absent summary contribution -- but that is inference, so this refuses loudly rather than
            // silently dropping the text or tripping a bare constraint violation. If this ever fires, the
            // fix is to make `vector` nullable, not to skip the write.
            throw new IllegalStateException("Entity " + ownerType + "#" + id + " has a concatenated text"
                    + " vector but an absent summary vector, which the entity-grain table cannot"
                    + " represent (its `vector` column is NOT NULL). This combination was believed"
                    + " impossible; please report it with the entity's shape.");
        }
        StoredSummaryRow storedRow = readStoredSummaryRow(connection, summaryTable, ownerType, id);
        if (storedRow != null && isUnchanged(storedRow.summary(), summary)
                && isUnchanged(storedRow.concatenated(), concatenated)) {
            return; // nothing about this row would change -- see the read-then-skip note in writeVectors
        }
        upsertSummaryVector(connection, summaryTable, ownerType, id, summary, concatenated,
                concatenated.isAbsent() ? null : vectorizable.concatenatedText());
    }

    /** Whether {@code entityType} declares a {@code @Summary} field of its own -- i.e. whether its
     *  entity-grain row is shared by mutations to other entities, which is what decides where it is
     *  written. */
    private static boolean isSummaryContainer(Class<?> entityType) {
        return !EntityReflection.fieldNamesAnnotatedWith(entityType, Summary.class).isEmpty();
    }

    // ---- summary recomputation: enqueue inside the transaction, recompute after it (OMI-255) ------

    /**
     * The declared {@code @Summary} shape of the registered model, built once the entity set is complete.
     *
     * <p>Lazily rather than in the constructor because registration is still in progress there --
     * {@code entityPackages(...)} scanning and every {@code JavAIPI.repository(...)} call add to it. By the
     * first save the set is necessarily final, since the {@code SessionFactory} has been built and
     * {@link #registerEntityType} refuses anything new past that point.
     */
    private Containment containment() {
        Containment resolved = containment;
        if (resolved == null) {
            synchronized (bootstrapLock) {
                resolved = containment;
                if (resolved == null) {
                    resolved = Containment.of(registeredEntityTypes);
                    containment = resolved;
                }
            }
        }
        return resolved;
    }

    /**
     * Which of the entities this save touched take part in {@code @Summary} containment at all.
     *
     * <p>Everything else is deliberately excluded, and that exclusion is the reason an ordinary JPA entity
     * is untouched by any of this: a plain {@code @Entity}, or a vectorized one that neither declares a
     * {@code @Summary} field nor is held in anyone else's, enqueues nothing, provisions no queue table, and
     * has its entity-grain row written inline exactly as before.
     */
    private Set<Containment.OwnerRef> participatingOwners(Object entity, Object managed) {
        Containment containment = containment();
        if (containment.hasNoSummaries()) {
            return Set.of();
        }
        Set<Containment.OwnerRef> owners = new LinkedHashSet<>();
        Map<UUID, Object> reachable = new HashMap<>(vectorizablesById(entity));
        reachable.putAll(vectorizablesById(managed));
        for (Object flushed : JavAIFlushVectorListener.current().persisted()) {
            Object resolved = resolve(flushed);
            UUID id = resolved == null ? null : idOrNull(resolved);
            if (id != null) {
                reachable.putIfAbsent(id, resolved);
            }
        }
        for (Map.Entry<UUID, Object> entry : reachable.entrySet()) {
            Class<?> type = entry.getValue().getClass();
            if (containment.participates(type)) {
                owners.add(new Containment.OwnerRef(type, entry.getKey()));
            }
        }
        return owners;
    }

    /** Writes the queue rows on the caller's own connection, so they commit or roll back with the mutation
     *  that caused them. */
    private void enqueueSummaries(Session session, Set<Containment.OwnerRef> owners) {
        if (owners.isEmpty()) {
            return;
        }
        ensurePendingSummaryTable();
        session.doWork(connection -> {
            for (Containment.OwnerRef owner : owners) {
                PendingSummaries.enqueue(connection, owner);
            }
        });
    }

    private void ensurePendingSummaryTable() {
        provisionTable(PendingSummaries.TABLE, PendingSummaries::createTable);
    }

    /**
     * Runs the recomputation once the caller's transaction has actually committed -- immediately when JavAI
     * owned that transaction, and via a commit callback when it did not.
     *
     * <p>The distinction is not cosmetic. Inside {@code JavAIPI.inTransaction} or a Spring
     * {@code @Transactional} method, {@code save} returns long before anything is committed; recomputing
     * there would read a graph no other session can see, and would write the shared summary row from inside
     * the very transaction this fix exists to keep it out of.
     */
    private void recomputeAfterCommit(Set<Containment.OwnerRef> owners) {
        Session ambient = ambientSession();
        if (ambient == null) {
            drain(owners);
            return;
        }
        SummaryDrainScope.afterCommit(this, ambient, owners, this::drain);
    }

    /**
     * Recomputes and writes the summary row for {@code owners} and everything containing them, transitively.
     *
     * <p>Runs in its own short transaction on its own connection: the caller's is committed and gone. Each
     * owner is recomputed under a Postgres advisory lock keyed on that owner, so two pods recomputing the
     * same container queue up instead of overwriting -- and because each recomputation reads the graph as
     * committed rather than folding in whatever the writer happened to hold, the one that runs last is
     * <em>correct</em>, not merely last. That is the difference between this and a lock inside the writer's
     * transaction, which cannot produce a correct value at all: it would serialise writers around the stale
     * snapshots they had already taken.
     *
     * <p><b>A failure here does not fail the caller's write, which is already committed.</b> The queue rows
     * survive, so the recomputation is owed, not lost -- the next save of the same container, an explicit
     * {@link JavAIPI#drainPendingSummaries}, or a reindex will do it. Silence would be wrong though, so it
     * is logged with the owners that were left pending.
     */
    private void drain(Set<Containment.OwnerRef> owners) {
        for (int attempt = 1; ; attempt++) {
            try {
                drainOnce(owners);
                return;
            } catch (RuntimeException e) {
                // Two pods recomputing the same container is ordinary, not exceptional, and it is precisely
                // what this whole mechanism is for -- so a conflict between two drains must not surface as a
                // failure. A retry is trivially correct here in a way it would not be inside the caller's
                // transaction: the drain derives everything it writes from committed state, so running it
                // again simply recomputes against whatever is now committed.
                if (attempt < DRAIN_MAX_ATTEMPTS && isTransientWriteConflict(e)) {
                    continue;
                }
                LOG.log(System.Logger.Level.WARNING, () -> "JavAI could not recompute summary vectors for "
                        + (owners == null ? "the pending queue" : owners)
                        + ". The write itself is committed and the recomputation stays queued -- the next save "
                        + "of the same container, JavAIPI.drainPendingSummaries(config), or a reindex will "
                        + "retry it. Until then those containers' summary vectors are stale.", e);
                return;
            }
        }
    }

    /** Whether {@code thrown} is a conflict that simply re-running would resolve -- a serialisation failure
     *  or a deadlock, as against a genuine error like a missing table or a bad mapping, which retrying would
     *  only repeat. */
    private static boolean isTransientWriteConflict(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if ("40001".equals(state) || "40P01".equals(state)) {
                    return true; // serialization_failure / deadlock_detected
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private void drainOnce(Set<Containment.OwnerRef> owners) {
        inOwnTransaction(session -> {
            PendingSummaries.Claim claim = session.doReturningWork(connection ->
                    owners == null ? PendingSummaries.claim(connection, DRAIN_BATCH_SIZE)
                            : PendingSummaries.claimFor(connection, owners));
            if (claim.isEmpty()) {
                return null;
            }
            Set<Containment.OwnerRef> visited = new LinkedHashSet<>();
            Deque<Containment.OwnerRef> pending = new ArrayDeque<>();
            for (PendingSummaries.PendingOwner owner : claim.owners()) {
                Class<?> type = resolveOwnerType(owner.ownerTypeName());
                if (type != null) {
                    pending.add(new Containment.OwnerRef(type, owner.ownerId()));
                }
            }
            while (!pending.isEmpty()) {
                Containment.OwnerRef owner = pending.poll();
                if (!visited.add(owner)) {
                    continue; // already recomputed in this pass -- also the cycle guard
                }
                recomputeOwner(session, owner);
                // Upward, one hop at a time, from what the database says contains this owner -- never
                // from the saving session's object graph, which may not have held the container at all.
                pending.addAll(containment().containersOf(session, owner.ownerType(), owner.ownerId()));
            }
            session.doWork(connection -> PendingSummaries.delete(connection, claim.rowIds()));
            return null;
        });
    }

    /**
     * Removes {@code (entityType, id)} from every container currently holding it, so the row can be deleted.
     *
     * <p>Done through the mapping rather than by deleting join rows directly: Hibernate owns the association
     * and knows its table and columns, and removing the element from the loaded collection lets it issue the
     * right statement. Deleting rows out from under it would also leave any collection already loaded in this
     * session holding an element that no longer exists.
     *
     * <p>This is a <b>membership</b> removal, never a cascade: the container loses its reference, and every
     * other entity is untouched. It was added (OMI-255) because only the membership-table half of this
     * existed, so deleting an entity worked or failed depending on how its container happened to declare the
     * field -- a distinction no caller deleting something should have had to know about. That other half is
     * gone with its table (OMI-277); this is the whole of it now.
     */
    private void detachFromContainers(Session session, Class<?> entityType, UUID id) {
        List<Containment.Edge> edges = containment().collectionEdgesHolding(entityType);
        if (edges.isEmpty()) {
            return;
        }
        Object child = session.find(entityType, id);
        for (Containment.Edge edge : edges) {
            for (Containment.OwnerRef owner : containment().ownersHolding(session, edge, entityType, id)) {
                Object container = session.find(owner.ownerType(), owner.ownerId());
                if (container == null || child == null) {
                    continue;
                }
                Object value = EntityReflection.readField(container, edge.fieldName());
                if (value instanceof Map<?, ?> map) {
                    map.values().removeIf(element -> element == child);
                } else if (value instanceof Collection<?> collection) {
                    collection.removeIf(element -> element == child);
                }
            }
        }
        // Flushed here, not left to the caller's own flush: the join rows have to be gone before the DELETE
        // of the entity itself is issued, and Hibernate is free to order the two either way otherwise.
        session.flush();
    }


    /** Recomputes one owner's entity-grain row from committed state, under a lock on that owner alone. */
    private void recomputeOwner(Session session, Containment.OwnerRef owner) {
        // Only a @Summary container has anything to recompute. Everything else in the queue is there purely
        // as a starting point for the walk upward -- its own entity-grain row depends on nothing but itself
        // and was already written, correctly, inline during the save (see writeEntityGrainVectors).
        //
        // Recomputing it anyway was not merely wasted work, it was expensive: doing so reloads the entity in
        // this fresh session and reads its vectors back out, and any leaf whose stored vectors don't make it
        // into the reloaded instance is then re-embedded for real. AssociationGraphEmbeddingCostE2ETest
        // counts embed() calls and caught exactly that -- four association *targets* re-embedded per save,
        // every one of them a leaf, none of them a container (OMI-255).
        if (!isSummaryContainer(owner.ownerType())) {
            return;
        }
        session.doWork(connection -> lockOwner(connection, owner));
        Object entity = session.find(owner.ownerType(), owner.ownerId());
        if (entity == null) {
            return; // deleted since it was enqueued; deleteById already removed its vector rows
        }
        if (!(entity instanceof JavAIVectorizable vectorizable)) {
            return;
        }
        hydrateVectors(session, entity);
        // The children's stored vectors too, not just this owner's: summaryVector() reads each @Summary
        // child's own summary, and an unhydrated child recomputes its vector from scratch -- a real
        // embedding call, per child, on every recomputation. One SELECT each is the cheaper half of that
        // trade by a wide margin.
        hydrateSummaryChildren(session, entity, Collections.newSetFromMap(new IdentityHashMap<>()));
        writeEntityGrainVectors(session, vectorizable, owner.ownerType().getName(), owner.ownerId());
    }

    private void hydrateSummaryChildren(Session session, Object entity, Set<Object> visited) {
        hydrateSummaryChildren(session, entity, visited, Map.of());
    }

    /**
     * @param originalsById the caller's own instances, by id, when this runs on the write path. A child the
     *                      caller held cannot be hydrated directly: their mutation landed on their detached
     *                      instance, so the merged copy carries the <em>new</em> field value on a
     *                      <em>pristine</em> slot, and hydrating that serves the old vector and persists it
     *                      (see {@link #hydrateVectors}'s own "load paths only" note). Hydrating the
     *                      <em>original</em> instead is safe and does the same job:
     *                      {@code JavAIRuntime.hydrateFieldVector} refuses any slot a setter has bumped, so
     *                      a genuinely-changed field keeps its dirty slot and is embedded for real, while an
     *                      untouched one is served its stored vector and then carried across by the same
     *                      transfer {@code save()} already performs. Empty on the read path, where every
     *                      instance is freshly loaded and there is no laundering to see through.
     */
    private void hydrateSummaryChildren(
            Session session, Object entity, Set<Object> visited, Map<UUID, Object> originalsById) {
        if (entity == null || !visited.add(entity)) {
            return;
        }
        for (String fieldName : EntityReflection.fieldNamesAnnotatedWith(entity.getClass(), Summary.class)) {
            Object value = EntityReflection.readField(entity, fieldName);
            for (Object child : expandToEntities(value)) {
                Object resolved = Hibernate.unproxy(child);
                UUID id = resolved == null ? null : idOrNull(resolved);
                Object original = id == null ? null : originalsById.get(id);
                if (original != null && original != resolved) {
                    hydrateVectors(session, original);
                    JavAIRuntime.transferComputedVectors(original, resolved);
                } else {
                    hydrateVectors(session, resolved);
                }
                hydrateSummaryChildren(session, resolved, visited, originalsById);
            }
        }
    }

    private static List<Object> expandToEntities(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new ArrayList<>(map.values());
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return value == null ? List.of() : List.of(value);
    }

    /**
     * A Postgres advisory lock keyed on {@code (owner_type, owner_id)}, held to the end of this drain's
     * transaction.
     *
     * <p>Advisory rather than row-level because the row may not exist yet -- a container being summarised
     * for the first time has nothing to lock -- and because it is held across the read-recompute-write
     * sequence rather than only across the write. It is a database lock, so it serialises pods, not merely
     * threads: JavAI's own in-process locking has no bearing on a second pod doing the same work.
     */
    private static void lockOwner(Connection connection, Containment.OwnerRef owner) throws SQLException {
        // The two-key form is (int, int), and hashtext() returns exactly int -- no cast, which is what the
        // (bigint, bigint) overload this first reached for does not have. Two keys rather than one hashed
        // string keeps types from colliding with each other's ids in the lock space.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))")) {
            statement.setString(1, owner.ownerType().getName());
            statement.setString(2, owner.ownerId().toString());
            statement.executeQuery().close();
        }
    }

    /** The registered entity class a queue row names, or null when this pod has no such class -- a row
     *  written by a deployment that knows a type this one doesn't is left alone rather than discarded. */
    private Class<?> resolveOwnerType(String typeName) {
        for (Class<?> type : registeredEntityTypes) {
            if (type.getName().equals(typeName)) {
                return type;
            }
        }
        return null;
    }

    /** A transaction of this backend's own, never the caller's -- the drain runs after the caller's has
     *  already committed, so there is nothing to join even when a scope is still bound to this thread. */
    private <T> T inOwnTransaction(Function<Session, T> work) {
        try (Session session = sessionFactory().openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                readCommitted(session);
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
     * Runs the drain's transaction at {@code READ COMMITTED}, whatever the application configured for its
     * own work.
     *
     * <p>Not a relaxation of anything -- it is what makes the recomputation correct. At
     * {@code REPEATABLE READ} a transaction's snapshot is fixed by its first statement, so a second pod's
     * drain would read the container as it was <em>before</em> the first pod's drain committed, and then be
     * refused when it tried to write the row: {@code could not serialize access due to concurrent update},
     * the very error this ticket started from, merely relocated. The advisory lock orders the two drains but
     * cannot move a snapshot that was already taken.
     *
     * <p>{@code READ COMMITTED} is exactly right for derived state: each drain wants the latest committed
     * graph, not a stable historical view of it. Statement-level snapshots mean the second drain sees the
     * first's work and folds in both writers' children. The retry in {@link #drain} remains as a backstop for
     * the case where the level cannot be set at all.
     */
    private static void readCommitted(Session session) {
        session.doWork(connection -> {
            try (Statement statement = connection.createStatement()) {
                // Plain SQL rather than Connection.setTransactionIsolation: the transaction has already
                // begun by this point, and the JDBC setter refuses that, while SET TRANSACTION is defined
                // precisely for it -- valid as the first statement of a transaction, which this is.
                statement.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED");
            }
        });
    }

    /** Drains everything queued, for a caller running this as maintenance rather than as part of a save.
     *  Loops until the queue is empty, since one pass claims at most {@link #DRAIN_BATCH_SIZE} owners. */
    void drainPendingSummaries() {
        if (containment().hasNoSummaries()) {
            return;
        }
        ensurePendingSummaryTable();
        while (pendingCount() > 0) {
            int before = pendingCount();
            drain(null);
            if (pendingCount() >= before) {
                // No progress -- either a drain is failing (already logged) or another pod is enqueueing at
                // least as fast as this one drains. Either way, spinning here would not help.
                return;
            }
        }
    }

    private int pendingCount() {
        return inSession(session -> session.doReturningWork(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + PendingSummaries.TABLE)) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }));
    }

    /** Overridden for the {@code QUEUE_ONLY}-then-drain half (OMI-255) -- the chunked, batch-warmed loop
     *  itself is the SPI's, shared with {@code reindexAll} and with the other two backends (OMI-266). */
    @Override
    public void reindex(Class<?> entityType) {
        reindexInChunks(entityType, SummaryPolicy.QUEUE_ONLY);
        drainPendingSummaries();
    }

    /** A vector as the database currently holds it, for comparison against the one about to be written. */
    private record StoredVector(String modelId, int dims, float[] values) {
    }

    /** The entity-grain row's two vectors, either of which may be absent. */
    private record StoredSummaryRow(StoredVector summary, StoredVector concatenated) {
    }

    /**
     * Whether writing {@code vector} would change the stored value at all -- including the two "both absent"
     * and "one absent" cases, so an entity that never had a concatenated text vector and still doesn't
     * counts as unchanged rather than as a difference between {@code null} and absent.
     *
     * <p>Values are compared exactly, not within a tolerance: these are the same floats that were stored,
     * round-tripped through pgvector's own shortest-round-trippable text form, so anything but an exact
     * match is a real difference. A tolerance would silently suppress small genuine changes -- which, for a
     * vector whose whole purpose is to rank by cosine distance, is the one error that would never surface.
     */
    private static boolean isUnchanged(StoredVector stored, EmbeddingVector vector) {
        if (vector == null || vector.isAbsent()) {
            return stored == null;
        }
        return stored != null
                && stored.dims() == vector.dims()
                && stored.modelId().equals(vector.modelId())
                && Arrays.equals(stored.values(), vector.values());
    }

    /** Every field vector already stored for one owner under the current model, in a single query. Returns
     *  empty when the table does not exist yet, which simply means nothing can be unchanged. */
    private static Map<String, StoredVector> readStoredFieldVectors(
            Connection connection, String ownerType, UUID ownerId, String modelId) throws SQLException {
        if (modelId == null) {
            return Map.of();
        }
        String table = FIELD_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        if (!tableExists(connection, table)) {
            return Map.of();
        }
        Map<String, StoredVector> stored = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT field_name, model_id, dims, vector::text FROM " + table
                        + " WHERE owner_type = ? AND owner_id = ?")) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    stored.put(rows.getString(1), new StoredVector(
                            rows.getString(2), rows.getInt(3), parseVectorLiteral(rows.getString(4))));
                }
            }
        }
        return stored;
    }

    private static StoredSummaryRow readStoredSummaryRow(
            Connection connection, String table, String ownerType, UUID ownerId) throws SQLException {
        if (!tableExists(connection, table)) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT model_id, dims, vector::text, concatenated_text_vector::text FROM " + table
                        + " WHERE owner_type = ? AND owner_id = ?")) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                String modelId = rows.getString(1);
                int dims = rows.getInt(2);
                String concatenated = rows.getString(4);
                return new StoredSummaryRow(
                        new StoredVector(modelId, dims, parseVectorLiteral(rows.getString(3))),
                        concatenated == null ? null
                                : new StoredVector(modelId, dims, parseVectorLiteral(concatenated)));
            }
        }
    }

    // ---- relational fields: singular (ordinary Hibernate @OneToOne) + collection (this backend's own) --

    /** Recursively assigns a random {@code UUID} to any reachable entity (this one, a singular related
     *  entity, a collection element, or a map value) whose {@code @Id} is still null -- there's no
     *  {@code @GeneratedValue}, so every entity's identity is application-assigned, cascaded relations
     *  included. Must run before {@code session.merge(...)}, not after: Hibernate needs the id in place to
     *  cascade-insert a related entity at all. */
    private static void ensureIdsAssigned(Object entity, Map<Object, Boolean> visited) {
        // An uninitialized association was loaded from the database, so it has an id already -- and
        // resolving it to confirm that would be exactly the load this walk must not cause (OMI-271).
        if (entity == null || !Hibernate.isInitialized(entity) || visited.put(entity, Boolean.TRUE) != null) {
            return;
        }
        // A JDK value is a leaf, never a node with an @Id somewhere inside it -- and reflecting into one is
        // not merely pointless, it throws: an @ElementCollection of Strings put this walk on
        // String.value, and the module system refuses to open java.lang for that (OMI-275). Checked here
        // rather than at each recursion site so no future caller has to remember it.
        if (entity.getClass().getName().startsWith("java.")) {
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
            if (!Hibernate.isInitialized(value)) {
                continue; // an unresolved collection holds only stored entities, which already have ids
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
    private void writeVectorsForRelatedEntities(Session session, Object entity, Map<UUID, Object> originalsById) {
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
            if (!Hibernate.isInitialized(value)) {
                // This save did not touch these members, so their stored vectors are still the right ones.
                // Iterating to find that out would load them cold and re-embed every one (OMI-271) -- the
                // exact per-member waste OMI-256 removed from the load path, reintroduced on the write path.
                continue;
            }
            if (value instanceof Map<?, ?> map) {
                writeVectorsForCollectionMembers(session, map.values(), originalsById);
            } else if (value instanceof Collection<?> collection) {
                writeVectorsForCollectionMembers(session, collection, originalsById);
            } else {
                writeVectorsForRelatedEntity(session, value, originalsById);
            }
        }
    }

    /**
     * Writes one related entity's vectors, resolving a Hibernate proxy first (OMI-161).
     *
     * <p><b>Why this can't just be {@code value instanceof JavAIVectorizable}.</b> {@code save()} merges the
     * caller's detached instance, and Hibernate's merged copy holds an <em>uninitialized proxy</em> for a
     * {@code FetchType.LAZY} singular association rather than loading the target. That proxy is a generated
     * subclass of the real entity, so it satisfies {@code instanceof JavAIVectorizable} perfectly well --
     * but its {@code @Id} field is never populated (a proxy delegates through an interceptor rather than
     * holding state), so {@code EntityReflection.readId} read {@code null} from it and the vector INSERT
     * died on {@code owner_id}'s NOT NULL constraint. {@code getClass()} would have been wrong too:
     * {@code Target$HibernateProxy$xyz}, not {@code Target}, so even a correct id would have been filed
     * under an {@code owner_type} nothing could ever look up again.
     *
     * <p><b>Uninitialized proxies are skipped, not initialized.</b> An untouched lazy association means the
     * caller never looked at that entity, so nothing about it changed and its vectors were already written
     * when it was saved through its own repository. Forcing initialization to "be safe" would cost a SELECT
     * per association per save, and -- because a freshly-loaded entity recomputes lazily -- potentially a
     * real embedding call for an entity nobody touched.
     *
     * <p><b>Initialized proxies are unwrapped and written.</b> Touching the association (say
     * {@code owner.getTarget().setLabel(...)}) initializes it, and that mutation genuinely does need a new
     * vector row -- so those resolve to the real instance and its real entity class. Skipping every proxy
     * indiscriminately would have traded this bug for a quieter one: a modified related entity whose vector
     * silently went stale.
     */
    /**
     * Hands the caller's already-computed vectors to Hibernate's managed copies, across the whole reachable
     * graph rather than just the saved root (OMI-187).
     *
     * <p>The root alone is not enough, and the owner-re-embedding this ticket started from is exactly why:
     * saving a {@code Tag} re-embeds its {@code TagSet}'s unchanged slug, and that {@code TagSet} is reached
     * through the <em>managed</em> tag, so it is Hibernate's instance, not the caller's. Matching the two
     * graphs by entity id is what lets the caller's computed vector reach it.
     *
     * <p>Identity is matched on {@code @Id}, not object identity -- the whole point is that these are two
     * different objects standing for the same row. Anything without an id (a JavAI collection, an entity
     * whose id has not been assigned) simply has no counterpart to match and is skipped.
     */
    private void transferVectorState(Object original, Object managed, Map<UUID, Object> byId) {
        if (original == managed || byId.isEmpty()) {
            return;
        }
        for (Object candidate : JavAIRuntime.reachableVectorizables(managed)) {
            Object target = resolve(candidate);
            UUID id = target == null ? null : idOrNull(target);
            if (id == null) {
                continue;
            }
            Object source = byId.get(id);
            if (source != null) {
                JavAIRuntime.transferComputedVectors(source, target);
            }
        }
    }

    /**
     * Carries the {@code @Version} Hibernate assigned during this write back onto the instances the caller
     * still holds (OMI-254).
     *
     * <p>Without this, {@code @Version} is annotatable but unusable. {@code merge()} performs the write on
     * its own managed copy and it is that copy whose version Hibernate increments, while {@code save()}
     * deliberately returns the caller's {@code entity} (see the comment at its {@code return}). So the object
     * handed back still carried the <em>pre-write</em> version, and saving it a second time -- with no
     * concurrency involved whatsoever, no second thread, no second transaction -- sent a version the database
     * had already moved past and threw {@link jakarta.persistence.OptimisticLockException}. Any code that
     * saves a graph, mutates it and saves it again therefore could not adopt optimistic locking at all, which
     * is precisely the read-modify-write shape a detached-entity repository encourages.
     *
     * <p>The invariant this establishes, and the one worth holding onto: <b>the object {@code save()} hands
     * back is safe to mutate and save again.</b>
     *
     * <p>The whole graph, not just the root: a cascaded child is written in this same flush and has its own
     * version bumped just as the root does, so refreshing only the root would leave the identical defect one
     * hop down. Matching is by {@code @Id} for the same reason {@link #transferVectorState} matches that way
     * -- these are two different objects standing for one row.
     *
     * <p>Detection is untouched by any of this. The version a concurrent writer collides on is the one
     * {@code merge()} reads off the detached instance <em>before</em> this runs; two threads that both loaded
     * version 4 still produce one winner and one {@code OptimisticLockException}, which
     * {@code OptimisticLockingTest} asserts directly rather than assuming.
     */
    private void refreshVersions(Object entity, Object managed) {
        if (entity == managed || !anyRegisteredTypeIsVersioned()) {
            return;
        }
        Map<UUID, Object> managedById = versionedEntitiesById(managed);
        if (managedById.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Object> target : versionedEntitiesById(entity).entrySet()) {
            Object source = managedById.get(target.getKey());
            if (source != null) {
                copyVersion(source, target.getValue());
            }
        }
    }

    /** Whether anything registered with this backend uses optimistic locking at all -- one check over the
     *  registry, so a codebase that has never written {@code @Version} pays nothing for it on every save.
     *  Not cached: the registry is small, and it grows as repositories are created. */
    private boolean anyRegisteredTypeIsVersioned() {
        for (Class<?> registered : registeredEntityTypes) {
            if (EntityReflection.versionField(registered) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every {@code @Version}-bearing entity reachable from {@code root}, keyed by {@code @Id}.
     *
     * <p>Walks entity references directly rather than {@link JavAIRuntime#reachableVectorizables}, because
     * optimistic locking is orthogonal to vectorization: a plain {@code @Entity} carrying a {@code @Version}
     * and no {@code @Vectorize} field anywhere is an ordinary thing to save, and the vectorizable walk --
     * which only ever reflects into nodes that are themselves {@code JavAIVectorizable} -- would find neither
     * it nor the root.
     *
     * <p>It also does not reuse {@link #reachableRelated}, for a specific reason: iterating a lazy collection
     * initializes it, and this walk runs on the write path where nothing else would have. Uninitialized
     * proxies and uninitialized lazy collections are therefore skipped rather than resolved. Forcing a load
     * to refresh a version on an association the caller never touched would trade the defect being fixed for
     * the one {@code savingDoesNotForceUninitializedLazyAssociationsToLoad} forbids -- and it would buy
     * nothing, since an untouched association was not written by this save, so its version did not move and
     * there is nothing to carry back.
     */
    private static Map<UUID, Object> versionedEntitiesById(Object root) {
        Map<UUID, Object> byId = new HashMap<>();
        collectVersionedEntities(root, byId, Collections.newSetFromMap(new IdentityHashMap<>()));
        return byId;
    }

    private static void collectVersionedEntities(Object node, Map<UUID, Object> byId, Set<Object> visited) {
        if (node == null || !Hibernate.isInitialized(node) || !visited.add(node)) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                collectVersionedEntities(value, byId, visited);
            }
            return;
        }
        if (node instanceof Collection<?> collection) {
            for (Object element : collection) {
                collectVersionedEntities(element, byId, visited);
            }
            return;
        }
        if (!node.getClass().isAnnotationPresent(Entity.class)) {
            return;
        }
        if (EntityReflection.versionField(node.getClass()) != null) {
            UUID id = idOrNull(node);
            if (id != null) {
                byId.put(id, node);
            }
        }
        for (Field field : EntityReflection.allFields(node.getClass())) {
            field.setAccessible(true);
            try {
                collectVersionedEntities(field.get(node), byId, visited);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + node.getClass(), e);
            }
        }
    }

    /** Both sides are the same entity type, so one field object reads and writes both. */
    private static void copyVersion(Object source, Object target) {
        Field field = EntityReflection.versionField(target.getClass());
        if (field == null || !field.getDeclaringClass().isInstance(source)) {
            return;
        }
        try {
            field.set(target, field.get(source));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot copy @Version field " + field + " onto " + target.getClass(), e);
        }
    }

    /**
     * Hands the caller's already-computed vectors to the managed entity's {@code @Summary} children before
     * anything reads its {@code summaryVector()}.
     *
     * <p>These children are the one case the ordinary transfer cannot reach. A lazy {@code @Summary}
     * association is still an uninitialized proxy when {@code save()} runs its up-front transfer, so it is
     * skipped there; it then materializes <em>inside</em> {@code JavAIRuntime.summaryVector}'s own recursion,
     * cold, and re-embeds a value the caller already had. That recursion is not a place persistence can hook.
     *
     * <p>Initializing the proxy here is not the "forced load" that
     * {@code savingDoesNotForceUninitializedLazyAssociationsToLoad} forbids: that guards <em>non-summary</em>
     * associations, which the vector walk genuinely never needs. A {@code @Summary} child is loaded moments
     * later regardless, because summarizing is defined in terms of it -- so this changes when it loads, not
     * whether.
     */
    private static void transferToSummaryChildren(Object managed, Map<UUID, Object> originalsById) {
        if (originalsById.isEmpty()) {
            return;
        }
        for (Field field : EntityReflection.allFields(managed.getClass())) {
            if (!field.isAnnotationPresent(dev.xtrafe.javai.annotations.Summary.class)) {
                continue;
            }
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(managed);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + field + " on " + managed.getClass(), e);
            }
            for (Object child : summaryChildren(value)) {
                Object related = Hibernate.unproxy(child);
                UUID id = idOrNull(related);
                if (id != null) {
                    JavAIRuntime.transferComputedVectors(originalsById.get(id), related);
                }
            }
        }
    }

    /** A {@code @Summary} field's vectorizable children, whether it holds one directly or a collection/map. */
    private static Collection<?> summaryChildren(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Map<?, ?> map) {
            return map.values();
        }
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        return List.of(value);
    }

    /** Every initialized, id-bearing vectorizable reachable from {@code root}, keyed by entity id. */
    private static Map<UUID, Object> vectorizablesById(Object root) {
        Map<UUID, Object> byId = new HashMap<>();
        for (Object candidate : JavAIRuntime.reachableVectorizables(root)) {
            Object resolved = resolve(candidate);
            UUID id = resolved == null ? null : idOrNull(resolved);
            if (id != null) {
                byId.put(id, resolved);
            }
        }
        return byId;
    }

    /**
     * The real instance behind a possible Hibernate proxy, or null if the proxy has never been initialized.
     *
     * <p>Unavoidable here, and for the reason OMI-161 already established: a proxy is a generated subclass,
     * so it satisfies {@code instanceof JavAIVectorizable} while holding none of the entity's field state --
     * including the woven {@code $javai$state} the vector caches live in. Reading through the proxy would
     * transfer onto the wrong object and silently do nothing. An uninitialized proxy is skipped rather than
     * resolved: forcing a load purely to move a cache entry would trade the embedding call we are trying to
     * avoid for a database round trip nobody asked for.
     */
    private static Object resolve(Object candidate) {
        if (!Hibernate.isInitialized(candidate)) {
            return null;
        }
        return Hibernate.unproxy(candidate);
    }

    /** {@code @Id} if this object has a readable one, else null -- JavAI collections and un-assigned
     *  entities both legitimately have none, and neither is an error worth propagating. */
    private static UUID idOrNull(Object candidate) {
        if (!candidate.getClass().isAnnotationPresent(Entity.class)) {
            return null;
        }
        try {
            return EntityReflection.readId(candidate);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void writeVectorsForRelatedEntity(Session session, Object value, Map<UUID, Object> originalsById) {
        if (value == null || !Hibernate.isInitialized(value)) {
            return;
        }
        Object related = Hibernate.unproxy(value);
        if (related instanceof JavAIVectorizable vectorizable
                && related.getClass().isAnnotationPresent(Entity.class)) {
            // Last chance to hand this instance the caller's already-computed vector. A lazy @Summary child
            // is still an uninitialized proxy when save() does its up-front transfer, so it is skipped there
            // (resolving it then would force a load nobody asked for); it only materializes here, when the
            // summary walk reads it -- cold, and about to re-embed a value the caller already has. OMI-187.
            UUID id = idOrNull(related);
            if (id != null) {
                JavAIRuntime.transferComputedVectors(originalsById.get(id), vectorizable);
            }
            writeVectors(session, related.getClass(), vectorizable);
        }
    }

    /** Writes vectors for the members of a <em>Hibernate-owned</em> (plain, natively-mapped) collection.
     *  Hibernate's own cascade INSERTs those members, but it has no idea this project's vector tables exist,
     *  so without this the members of a plain {@code @OneToMany}/{@code @ManyToMany} would persist relationally
     *  yet never get a vector row -- silently breaking the "every {@code @JavAIVectorizable} written through a
     *  repository has an up-to-date, persisted vector" guarantee. JavAI collection fields are excluded by the
     *  caller because {@link #syncCollectionMembers} already does this for them. */
    private void writeVectorsForCollectionMembers(Session session, Collection<?> members,
            Map<UUID, Object> originalsById) {
        for (Object member : members) {
            // Same proxy resolution as a singular association -- a lazily-mapped element can be a proxy
            // here just as readily (OMI-161). This used to rely on `@Entity` not being inherited by the
            // generated proxy subclass to skip them, which happened to avoid the crash but also silently
            // dropped *initialized* proxies whose entity the caller had genuinely modified.
            writeVectorsForRelatedEntity(session, member, originalsById);
        }
    }


    /** One persisted collection/map member -- {@code key} is {@code null} for a {@code Collection} member,
     *  or the map key (stringified) for a {@code Map} value. */




    /**
     * Serves an entity every piece of state this backend stores <em>outside</em> its own table, in one read.
     *
     * <p>Three things live out-of-band: each {@code @Vectorize} field's vector, the entity-grain concatenated
     * text vector, and any {@code Point} field. They used to be fetched by three different mechanisms at
     * three different times -- two queries here plus a JDBC metadata call each, and a separate recursive walk
     * for geo. That walk is what OMI-276 broke: it ran before the caller could initialize anything, so a
     * {@code Point} on an entity reached through an association was silently never read.
     *
     * <p>Fetching them together fixes that and costs less than the two queries did, which is the point --
     * OMI-275's standing criterion is that a correctness fix must not be bought with round trips. One
     * statement per entity, over only the tables that apply to this entity and actually exist, with existence
     * memoised in {@link #confirmedTables} so the metadata call is paid once per table rather than once per
     * entity.
     *
     * @param includeGeo whether to restore {@code Point} fields as well. False on the write path, which
     *                   reads vectors onto the caller's <em>own</em> instance ({@code hydrateSummaryChildren})
     *                   and must not overwrite a {@code Point} that caller just set; the values there flow
     *                   the other way, through {@code syncGeoPoints}.
     */
    private void hydrateOutOfBand(Session session, Object entity, boolean includeGeo) {
        if (entity == null || !entity.getClass().isAnnotationPresent(Entity.class)) {
            return;
        }
        Class<?> entityType = entity.getClass();
        UUID ownerId = idOrNull(entity);
        if (ownerId == null) {
            return;
        }
        boolean vectorizable = entity instanceof JavAIVectorizable;
        String modelId = vectorizable ? JavAIRuntime.currentModelId() : null;
        Set<String> fieldNames = vectorizable ? EntityReflection.vectorizeFieldNames(entityType) : Set.of();
        // A model the provider cannot name has no table to read, but that says nothing about geo -- which is
        // model-independent, and is why this is three separate decisions rather than one early return.
        boolean wantsFieldVectors = modelId != null && !fieldNames.isEmpty();
        boolean wantsConcatenated = modelId != null && JavAIRuntime.participatesInConcatenation(entityType);
        List<Field> pointFields = includeGeo ? pointFieldsOf(entityType) : List.of();
        if (!wantsFieldVectors && !wantsConcatenated && pointFields.isEmpty()) {
            return; // the common case for a plain entity: no statement at all
        }

        String ownerType = entityType.getName();
        String fieldTable = modelId == null ? null : FIELD_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        String summaryTable = modelId == null ? null : SUMMARY_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);

        session.doWork(connection -> {
            List<String> branches = new ArrayList<>();
            if (wantsFieldVectors && tableExistsCached(connection, fieldTable)) {
                branches.add("SELECT 'v'::text, field_name::text, model_id::text, dims::int,"
                        + " computed_at::timestamptz, vector::text, NULL::float8, NULL::float8"
                        + " FROM " + fieldTable + " WHERE owner_type = ? AND owner_id = ?");
            }
            if (wantsConcatenated && tableExistsCached(connection, summaryTable)) {
                branches.add("SELECT 'c'::text, NULL::text, model_id::text, dims::int,"
                        + " concatenated_text_computed_at::timestamptz, concatenated_text_vector::text,"
                        + " NULL::float8, NULL::float8"
                        + " FROM " + summaryTable + " WHERE owner_type = ? AND owner_id = ?");
            }
            if (!pointFields.isEmpty() && tableExistsCached(connection, "javai_geo_points")) {
                branches.add("SELECT 'g'::text, field_name::text, NULL::text, NULL::int,"
                        + " NULL::timestamptz, NULL::text, longitude::float8, latitude::float8"
                        + " FROM javai_geo_points WHERE owner_type = ? AND owner_id = ?");
            }
            if (branches.isEmpty()) {
                return; // nothing written for this model yet, or no geo table: recompute rather than read
            }
            // Read positionally, never by label: a UNION takes its column names from whichever branch
            // happens to be first, and which branch that is depends on this entity's own shape.
            try (PreparedStatement statement = connection.prepareStatement(String.join(" UNION ALL ", branches))) {
                int parameter = 1;
                for (int i = 0; i < branches.size(); i++) {
                    statement.setString(parameter++, ownerType);
                    statement.setObject(parameter++, ownerId);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        switch (rows.getString(1)) {
                            case "v" -> hydrateOneFieldVector(entity, fieldNames, rows);
                            case "c" -> hydrateOneConcatenatedTextVector(entity, rows);
                            case "g" -> hydrateOnePoint(entity, pointFields, rows);
                            default -> throw new IllegalStateException("unknown out-of-band row kind");
                        }
                    }
                }
            }
        });
    }

    /** Vectors only, for the write path -- see {@link #hydrateOutOfBand}'s {@code includeGeo} parameter. */
    private void hydrateVectors(Session session, Object entity) {
        hydrateOutOfBand(session, entity, false);
    }

    private static void hydrateOneFieldVector(Object entity, Set<String> fieldNames, ResultSet rows)
            throws SQLException {
        String fieldName = rows.getString(2);
        if (!fieldNames.contains(fieldName)) {
            // COMBINED_VECTOR_FIELD and any field no longer annotated: stored, but not a slot anything
            // reads. vector()/summaryVector() recombine from field slots.
            return;
        }
        float[] values = parseVectorLiteral(rows.getString(6));
        JavAIRuntime.hydrateFieldVector(entity, fieldName, new EmbeddingVector(
                values, rows.getString(3), rows.getInt(4), rows.getTimestamp(5).toInstant()));
    }

    /**
     * Restores a loaded entity's concatenated text vector from its stored row (OMI-191).
     *
     * <p>Matters more than hydrating a field vector. A loaded entity's {@code summaryVector()} is arithmetic
     * over field vectors hydration already restored, so recomputing costs nothing; the concatenated text
     * vector is a real embedding, so not restoring it would mean a live model call on every load.
     */
    private static void hydrateOneConcatenatedTextVector(Object entity, ResultSet rows) throws SQLException {
        String literal = rows.getString(6);
        Timestamp computedAt = rows.getTimestamp(5);
        if (literal == null || computedAt == null) {
            return; // stored without concatenation, or the opt-in was switched off
        }
        float[] values = parseVectorLiteral(literal);
        JavAIRuntime.hydrateConcatenatedTextVector(entity, new EmbeddingVector(
                values, rows.getString(3), values.length, computedAt.toInstant()));
    }

    private static void hydrateOnePoint(Object entity, List<Field> pointFields, ResultSet rows)
            throws SQLException {
        String fieldName = rows.getString(2);
        Point point = new Point(rows.getDouble(7), rows.getDouble(8));
        for (Field field : pointFields) {
            if (field.getName().equals(fieldName)) {
                try {
                    field.set(entity, point);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot write Point field " + field, e);
                }
                return;
            }
        }
    }

    /** {@code Point}-typed fields per entity class, resolved once: this runs for every entity Hibernate
     *  loads, so the reflection behind it must not. */
    private static final Map<Class<?>, List<Field>> POINT_FIELDS = new ConcurrentHashMap<>();

    private static List<Field> pointFieldsOf(Class<?> entityType) {
        return POINT_FIELDS.computeIfAbsent(entityType, type -> {
            List<Field> fields = new ArrayList<>();
            for (Field field : EntityReflection.allFields(type)) {
                if (Point.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
            return List.copyOf(fields);
        });
    }

    /**
     * The whole post-load step, in the one place every load path shares (OMI-256).
     *
     * <p>{@code findById}, {@code findAll}, the derived-finder path and vector search all have to do exactly
     * this, and each used to do it by repeating the same three calls in its own body. Deepening the last of
     * them to cover association members meant changing it in four places, which is the argument for this
     * method existing: a load path that gets this list wrong does not fail, it silently costs a model call,
     * so the list is worth stating once rather than four times.
     *
     * <p>{@code visited} is shared across every root in a multi-row load deliberately. Two rows of one
     * {@code findAll} routinely reach the same association target, and it only needs hydrating once -- the
     * second visit would be a redundant SELECT for a slot already filled.
     */
    /** {@link #tableExists} with the confirmation memoised -- see {@link #confirmedTables}. */
    private boolean tableExistsCached(Connection connection, String table) throws SQLException {
        if (confirmedTables.contains(table)) {
            return true;
        }
        if (!tableExists(connection, table)) {
            return false;
        }
        confirmedTables.add(table);
        return true;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
            return tables.next();
        }
    }

    /** Inverse of {@link #toVectorLiteral} -- pgvector renders as {@code [1.0,2.0,3.0]}. */
    private static float[] parseVectorLiteral(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return values;
    }

    /**
     * Removes whatever vector a previous save stored for {@code fieldName}, because this save has none.
     *
     * <p>Targets only the currently-configured model's table: vectors are stored per model precisely so
     * several models can coexist, and a field losing its content under today's model says nothing about
     * a vector another model wrote. No table means nothing was ever written, which is not an error.
     */
    private static void deleteFieldVectorRow(Connection connection, String modelId, String ownerType,
            UUID ownerId, String fieldName) throws SQLException {
        if (modelId == null) {
            return; // provider can't name its model, so there is no table to target -- see modelId()'s javadoc
        }
        String table = FIELD_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        if (!tableExists(connection, table)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE owner_type = ? AND owner_id = ? AND field_name = ?")) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, fieldName);
            statement.executeUpdate();
        }
    }

    /** {@link #deleteFieldVectorRow}'s counterpart for the summary table, which is keyed by owner alone. */
    private static void deleteSummaryVectorRow(Connection connection, String modelId, String ownerType,
            UUID ownerId) throws SQLException {
        if (modelId == null) {
            return;
        }
        String table = SUMMARY_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        if (!tableExists(connection, table)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE owner_type = ? AND owner_id = ?")) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.executeUpdate();
        }
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

    /**
     * Writes the entity-grain row: the summary vector, plus the concatenated text and its vector when the
     * entity participates (OMI-191).
     *
     * <p>The concatenated columns are always assigned, never left alone -- passing an absent
     * {@code concatenated} writes NULLs, which is how turning {@code @Summary(concatenate = true)} off
     * clears a previously-stored text vector instead of leaving it to keep matching searches.
     */
    private static void upsertSummaryVector(Connection connection, String table, String ownerType, UUID ownerId,
            EmbeddingVector vector, EmbeddingVector concatenated, String concatenatedText) throws SQLException {
        String sql = "INSERT INTO " + table + " (owner_type, owner_id, model_id, dims, vector, computed_at,"
                + " concatenated_text, concatenated_text_vector, concatenated_text_computed_at) "
                + "VALUES (?, ?, ?, ?, ?::vector, ?, ?, ?::vector, ?) "
                + "ON CONFLICT (owner_type, owner_id) "
                + "DO UPDATE SET dims = EXCLUDED.dims, vector = EXCLUDED.vector,"
                + " computed_at = EXCLUDED.computed_at,"
                + " concatenated_text = EXCLUDED.concatenated_text,"
                + " concatenated_text_vector = EXCLUDED.concatenated_text_vector,"
                + " concatenated_text_computed_at = EXCLUDED.concatenated_text_computed_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, vector.modelId());
            statement.setInt(4, vector.dims());
            statement.setString(5, toVectorLiteral(vector.values()));
            statement.setTimestamp(6, Timestamp.from(vector.computedAt()));
            if (concatenated == null || concatenated.isAbsent()) {
                statement.setNull(7, java.sql.Types.VARCHAR);
                statement.setNull(8, java.sql.Types.VARCHAR);
                statement.setNull(9, java.sql.Types.TIMESTAMP);
            } else {
                statement.setString(7, concatenatedText);
                statement.setString(8, toVectorLiteral(concatenated.values()));
                statement.setTimestamp(9, Timestamp.from(concatenated.computedAt()));
            }
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
    /** One ranked hit as the vector table answers it, before the entity behind it is loaded. {@code distance}
     *  is pgvector's own cosine distance; {@link Ranked} carries the similarity it converts to. */
    private record RankedId(UUID id, double distance) {
    }

    /**
     * {@code vectorColumn} names which vector to rank by -- the entity-grain table holds two (OMI-191).
     * Rows where it is NULL are excluded, so a non-participating entity never surfaces as a match.
     *
     * <p>{@code allowedIds} is the narrowing predicate already resolved to the ids that satisfy it, or
     * {@code null} for an unnarrowed search (OMI-230). Passed as a single {@code uuid[]} rather than an
     * {@code IN} list built by string concatenation: one bound parameter regardless of how many ids there
     * are, so the statement text stays constant and re-plannable however selective the predicate was.
     */
    private static List<RankedId> rankIds(Connection connection, String table, Class<?> entityType,
            String fieldName, EmbeddingVector reference, int limit, String vectorColumn, List<UUID> allowedIds)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT owner_id, ").append(vectorColumn)
                .append(" <=> ?::vector AS javai_distance FROM ").append(table)
                .append(" WHERE owner_type = ?");
        if (fieldName != null) {
            sql.append(" AND field_name = ?");
        }
        sql.append(" AND ").append(vectorColumn).append(" IS NOT NULL");
        if (allowedIds != null) {
            sql.append(" AND owner_id = ANY(?)");
        }
        sql.append(" ORDER BY javai_distance LIMIT ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, toVectorLiteral(reference.values()));
            statement.setString(index++, entityType.getName());
            if (fieldName != null) {
                statement.setString(index++, fieldName);
            }
            if (allowedIds != null) {
                statement.setArray(index++, connection.createArrayOf("uuid", allowedIds.toArray()));
            }
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RankedId> ranked = new ArrayList<>();
                while (resultSet.next()) {
                    ranked.add(new RankedId(
                            (UUID) resultSet.getObject("owner_id"), resultSet.getDouble("javai_distance")));
                }
                return ranked;
            }
        }
    }

    /** Second half: a targeted load of exactly the ranked ids, one {@code find} per id (bounded by
     *  {@code limit}, typically small) -- simpler and just as correct as a batch multi-load API for this
     *  volume, and trivially preserves rank order without a separate re-sort step. Each hit keeps the
     *  distance it was ranked on, converted to the cosine similarity {@link Ranked} promises. */
    private List<Ranked<Object>> hydrateRanked(Class<?> entityType, List<RankedId> ranked) {
        return inSession(session -> {
            List<Ranked<Object>> results = new ArrayList<>(ranked.size());
            for (RankedId hit : ranked) {
                Object entity = session.find(entityType, hit.id());
                if (entity != null) {
                    // pgvector's <=> is cosine distance; Ranked speaks the cosine similarity the rest of
                    // JavAI does, so a hit's score means the same as VectorMath.cosineSimilarity would.
                    results.add(new Ranked<>(entity, 1.0 - hit.distance()));
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

    /**
     * Creates {@code javai_vectors__<model>} (fixed to {@code dims} from the moment it's created -- known
     * upfront, since only one model's vectors will ever land in this specific table) if it doesn't already
     * exist, and returns its name.
     *
     * <p><b>Provisioned out of band, not inside the caller's transaction (OMI-255).</b> This DDL used to run
     * on the caller's own connection, before every single vector write. That is a deadlock generator, and it
     * fired before any of the contention this ticket was filed about: {@code CREATE INDEX IF NOT EXISTS}
     * takes a {@code ShareLock} on the table even when there is nothing to create, and {@code ShareLock}
     * conflicts with the {@code RowExclusiveLock} a concurrent transaction already holds from its own
     * INSERT into the same table. Two savers, each holding what the other needs:
     *
     * <pre>
     * Process 69 waits for RowExclusiveLock on relation 16875; blocked by process 71.
     * Process 71 waits for RowExclusiveLock on relation 16875; blocked by process 69.
     * </pre>
     *
     * <p>Running it on its own connection, committed independently, removes the conflict: the DDL
     * transaction is short, touches no rows, and is over before the caller's INSERT is issued.
     *
     * <p><b>And that is what makes memoizing it safe again.</b> The previous implementation re-ran the DDL
     * every time specifically because it could not cache: DDL is transactional in Postgres, so a later
     * failure in the caller's transaction rolled the {@code CREATE TABLE} back too and left an in-memory
     * "already created" flag permanently lying. Committing separately means the table's existence no longer
     * depends on the caller's outcome, so {@link #provisionedTables} can be trusted -- the cache and the
     * out-of-band execution are one change, not two.
     */
    private String ensureFieldVectorTable(String modelId, int dims) {
        String table = FIELD_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        provisionTable(table, statement -> {
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
        });
        return table;
    }

    /**
     * The <b>entity-grain</b> vector table: one row per {@code (owner_type, owner_id)}.
     *
     * <p>Despite its name it holds more than the summary vector (OMI-191). The concatenated text vector is
     * also entity-level, and this is exactly the right grain for it -- the field table is keyed
     * {@code (owner_type, owner_id, field_name)}, so a per-entity value there would be null on every row but
     * one, with an arbitrary convention for which row carries it. The table keeps its name deliberately:
     * renaming it would be a migration bought for cosmetics. Read it as "entity-level vectors", of which the
     * summary vector is one and the concatenated text vector another.
     *
     * <p>{@code concatenated_text} is stored alongside its vector so that re-embedding under a different
     * model is a pure re-embed rather than a fresh walk of the object graph -- text is model-independent.
     * Postgres TOASTs a {@code text} column automatically, compressing and storing it out-of-line when
     * large, which is the right behaviour for accumulated subtree text with nothing to configure.
     */
    private String ensureSummaryVectorTable(String modelId, int dims) {
        String table = SUMMARY_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        provisionTable(table, statement -> {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "owner_type   varchar(255) NOT NULL,"
                    + "owner_id     uuid         NOT NULL,"
                    + "model_id     varchar(128) NOT NULL,"
                    + "dims         integer      NOT NULL,"
                    + "vector       vector(" + dims + ") NOT NULL,"
                    + "concatenated_text             text        NULL,"
                    + "concatenated_text_vector      vector(" + dims + ") NULL,"
                    + "concatenated_text_computed_at timestamptz NULL,"
                    + "computed_at  timestamptz  NOT NULL,"
                    + "PRIMARY KEY (owner_type, owner_id))");
            // CREATE TABLE IF NOT EXISTS does nothing to a table that already exists, so a deployment that
            // ran before OMI-191 would keep a three-column-short table and fail on first write. Adding the
            // columns separately is the migration, and it is idempotent.
            statement.execute("ALTER TABLE " + table
                    + " ADD COLUMN IF NOT EXISTS concatenated_text text NULL");
            statement.execute("ALTER TABLE " + table
                    + " ADD COLUMN IF NOT EXISTS concatenated_text_vector vector(" + dims + ") NULL");
            statement.execute("ALTER TABLE " + table
                    + " ADD COLUMN IF NOT EXISTS concatenated_text_computed_at timestamptz NULL");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_lookup ON " + table + " (owner_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_hnsw ON " + table + " USING hnsw (vector vector_cosine_ops)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_concat_hnsw ON " + table
                    + " USING hnsw (concatenated_text_vector vector_cosine_ops)");
        }, "concatenated_text", "concatenated_text_vector", "concatenated_text_computed_at");
        return table;
    }

    /** The DDL a {@link #provisionTable} call runs, on a connection this backend opened for that purpose
     *  alone. Separate from {@code SQLConsumer}-style generality on purpose: nothing else may run here. */
    private interface TableDdl {
        void run(Statement statement) throws SQLException;
    }

    /**
     * Runs {@code ddl} once per table name, on its own connection and its own committed transaction --
     * never the caller's. See {@link #ensureFieldVectorTable} for why both halves of that matter.
     *
     * <p><b>Two processes may reach this at the same moment,</b> and {@code IF NOT EXISTS} is not atomic
     * against a concurrent creator: Postgres checks the catalog, then inserts into it, and a second creator
     * landing between those two steps gets a unique-violation on {@code pg_class}/{@code pg_type} rather than
     * the silent no-op the syntax implies. Losing that race means the table now exists, which is the outcome
     * this method exists to produce -- so it is a success, not a failure, and is verified as one rather than
     * assumed.
     */
    private void provisionTable(String table, TableDdl ddl, String... requiredColumns) {
        provisionedTables.computeIfAbsent(table, name -> {
            // Steady state: the table is already there and already migrated, so issue no DDL at all. This is
            // not only an optimisation. A ShareLock request against a table some other pod is mid-INSERT
            // into would block until that pod committed -- harmless but pointless, and paid on every
            // process start. Reading the catalog takes no lock anything else can conflict with.
            if (isAlreadyProvisioned(name, requiredColumns)) {
                return Boolean.TRUE;
            }
            try (Session session = sessionFactory().openSession()) {
                Transaction tx = session.beginTransaction();
                try {
                    session.doWork(connection -> {
                        try (Statement statement = connection.createStatement()) {
                            ddl.run(statement);
                        }
                    });
                    tx.commit();
                } catch (RuntimeException e) {
                    if (tx.isActive()) {
                        tx.rollback();
                    }
                    if (!tableExistsInOwnTransaction(name)) {
                        throw e;
                    }
                    // Lost the creation race; the table is there, which is all this call promised.
                }
            }
            return Boolean.TRUE;
        });
    }

    /** Existence checked on a connection of its own, so a rolled-back provisioning attempt cannot leave the
     *  answer entangled with the transaction that failed. */
    private boolean tableExistsInOwnTransaction(String table) {
        try (Session session = sessionFactory().openSession()) {
            return session.doReturningWork(connection -> tableExists(connection, table));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether {@code table} exists <em>and</em> already carries every column the current DDL would add --
     *  the second half is what keeps a pre-OMI-191 table (created before the concatenated-text columns
     *  existed) from being mistaken for an up-to-date one and never migrated. */
    private boolean isAlreadyProvisioned(String table, String... requiredColumns) {
        try (Session session = sessionFactory().openSession()) {
            return session.doReturningWork(connection -> {
                if (!tableExists(connection, table)) {
                    return false;
                }
                for (String column : requiredColumns) {
                    try (ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
                        if (!columns.next()) {
                            return false;
                        }
                    }
                }
                return true;
            });
        } catch (RuntimeException e) {
            return false; // can't tell -- fall through to the idempotent DDL, which is safe either way
        }
    }

    // ---- lazy bootstrap -----------------------------------------------------------------------

    /** Package-private rather than private so {@link JavAIPI#sessionFactory(JavAIPersistenceConfig)} can
     *  hand this exact instance to a caller wiring their own Spring transaction manager (OMI-160). */
    /**
     * The nearest caller outside JavAI's own plumbing, as {@code Class.method(File:line)}.
     *
     * <p>Used only to explain a failure. The two ways to build the factory -- the first method call on any
     * repository, and {@code JavAIPI.sessionFactory(config)} -- both reach here through several JavAI
     * frames (a dynamic proxy, an invocation handler, a backend method), so the useful frame is the first
     * one past them.
     *
     * <p>Skipping is by <em>class</em> rather than by package prefix, deliberately: this project's own
     * tests live in {@code dev.xtrafe.javai.persistence} too, so a prefix filter would discard the very
     * frame that identifies the caller and report "unknown" for exactly the case being tested.
     */
    private static String describeCallingSite() {
        Set<String> ourFrames = Set.of(
                RepositoryBackendHibernatePostgres.class.getName(),
                RepositoryInvocationHandler.class.getName(),
                JavAIPI.class.getName());
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .filter(frame -> !ourFrames.contains(frame.getClassName())
                                && !isGeneratedProxy(frame.getClassName())
                                && !frame.getClassName().startsWith("java.lang.reflect.")
                                && !frame.getClassName().startsWith("java.lang.invoke."))
                        .findFirst()
                        .map(frame -> frame.getClassName() + "." + frame.getMethodName()
                                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")")
                        .orElse("(no caller outside JavAI on the stack)"));
    }

    /** A JDK dynamic proxy frame. Named for the proxied interface's package when that interface is not
     *  public, so a {@code jdk.proxy} prefix check misses exactly the repositories this project declares. */
    private static boolean isGeneratedProxy(String className) {
        return className.startsWith("jdk.proxy") || className.contains(".$Proxy") || className.startsWith("$Proxy");
    }

    SessionFactory sessionFactory() {
        SessionFactory factory = sessionFactory;
        if (factory != null) {
            return factory;
        }
        synchronized (bootstrapLock) {
            if (sessionFactory == null) {
                // Captured before the build, so a later late-registration failure can name the call that
                // closed the registration window rather than only the type that arrived after it (OMI-214).
                factoryBuildTrigger = describeCallingSite();
                sessionFactory = config.externalSessionFactory() != null
                        ? nativeFactory(config.externalSessionFactory())
                        : buildSessionFactory();
                registerFlushVectorListener(sessionFactory);
                registerPostLoadVectorListener(sessionFactory);
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

    /** {@link #FLUSH_LISTENER_REGISTERED}'s counterpart for the post-load hydration listener, kept separate
     *  so neither registration can mask the other's absence. */
    private static final Set<SessionFactory> POST_LOAD_LISTENER_REGISTERED =
            Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Tables this backend has confirmed exist. Positives only, deliberately: a table cannot stop existing,
     * so a hit is permanently safe -- while a miss must stay a miss, since the very next write may create it
     * (a first run, or a switch to a model whose vector table has never been written). Without this the
     * per-entity read below pays a JDBC metadata round trip per table per entity, which is a cost nobody
     * measured because it never appeared in {@code pg_stat_user_tables} (OMI-276).
     */
    private final Set<String> confirmedTables = ConcurrentHashMap.newKeySet();

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

    /** Attaches {@link JavAIPostLoadVectorListener}, so an entity is served its stored vectors when Hibernate
     *  materializes it rather than when some walk goes looking (OMI-271). Registered on the same factories
     *  and under the same identity-keyed guard as the flush listener above. */
    private void registerPostLoadVectorListener(SessionFactory factory) {
        synchronized (POST_LOAD_LISTENER_REGISTERED) {
            if (!POST_LOAD_LISTENER_REGISTERED.add(factory)) {
                return;
            }
        }
        EventListenerRegistry listeners = ((SessionFactoryImplementor) factory)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        listeners.appendListeners(EventType.POST_LOAD,
                new JavAIPostLoadVectorListener((session, entity) -> hydrateOutOfBand(session, entity, true)));
    }

    /** Writes vectors for every {@code @JavAIVectorizable} Hibernate reported persisting in this flush.
     *  Complements -- never replaces -- the explicit walk above: an entity whose mapped columns didn't change
     *  produces no Hibernate event at all, which is exactly the case {@code reindexAll()} relies on. */
    private void writeVectorsForFlushedEntities(Session session, Map<UUID, Object> originalsById) {
        for (Object entity : JavAIFlushVectorListener.current().persisted()) {
            // Proxy-resolving, for consistency with the two explicit walks above (OMI-161).
            writeVectorsForRelatedEntity(session, entity, originalsById);
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

    /** The pgvector extension, and the geo side table. The per-model vector tables are created lazily, on
     *  first write, because each needs its model's dimension known upfront; neither of these does. */
    private static void initializeSchema(SessionFactory factory) {
        try (Session session = factory.openSession()) {
            session.doWork(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS vector");


                    // Geo Point support (OMI-141): the cube + earthdistance contrib extensions (bundled with
                    // the official Postgres base image, so no PostGIS/hibernate-spatial dependency and no
                    // image swap) give great-circle distance in meters via earth_distance(ll_to_earth(...)).
                    // Point fields are @Transient and round-trip through this side table.
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
