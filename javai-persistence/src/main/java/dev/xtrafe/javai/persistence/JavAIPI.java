package dev.xtrafe.javai.persistence;

import org.hibernate.SessionFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * JavAI Persistence Interface -- the save/query/re-index contract JavAI objects speak internally, per
 * doc/spec/persistence-bridge.md. Realizes a {@code JavAIRepository<Entity>} subinterface as a dynamic
 * {@link Proxy}, backed by whichever {@link JavAIPersistenceConfig.Backend} the caller passes in.
 *
 * <pre>{@code
 * interface ArticleRepository extends JavAIRepository<Article> {
 *     List<Article> findNearestByBodyVector(EmbeddingVector reference, int limit);
 * }
 * JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
 *         .backend(JavAIPersistenceConfig.Backend.POSTGRES)
 *         .postgresUrl(...).postgresUsername(...).postgresPassword(...)
 *         .build();
 * ArticleRepository repo = JavAIPI.repository(ArticleRepository.class, config);
 * repo.save(article);
 * List<Article> hits = repo.findNearestByBodyVector(queryVector, 20);
 * }</pre>
 *
 * <p><b>No ambient configuration.</b> {@link #repository(Class, JavAIPersistenceConfig)} takes its
 * {@link JavAIPersistenceConfig} explicitly, every call -- there is no "configure once, read anywhere"
 * static pointer to accidentally clobber or race. A caller wanting the old self-contained,
 * system-property-derived default builds it explicitly too: {@code
 * JavAIPI.repository(X.class, JavAIPersistenceConfig.fromSystemProperties())}. Reuse the *same*
 * {@link JavAIPersistenceConfig} instance across every {@code repository(...)} call meant to share one
 * backend connection/pool -- {@code config} has no {@code equals()}/{@code hashCode()} override, so the
 * internal backend cache below is keyed by reference identity; a fresh {@code .builder()...build()} call
 * with identical field values on every invocation would silently multiply backends instead of sharing one.
 *
 * <p><b>Derived query naming</b>: {@code findNearestBy<Field>Vector} names the SAME per-field accessor the
 * weaver already synthesizes in memory (e.g. {@code bodyVector()} -> {@code findNearestByBodyVector}) --
 * {@code <Field>} must be a real {@code @Vectorize} field. Two whole-object variants are also recognized:
 * {@code findNearestByVector} (the object's own combined {@code vector()}) and
 * {@code findNearestBySummaryVector} ({@code summaryVector()}). <b>Ordinary Spring-Data-style relational
 * finders</b> ({@code findBy…}/{@code existsBy…}/{@code countBy…}/{@code deleteBy…}, OMI-138) are recognized
 * too -- parsed and validated via {@link DerivedFinderQuery}, then checked for backend feasibility -- and
 * resolved against the entity's own mapped columns. Only a name matching neither convention is rejected
 * here, at repository-creation time, not on first call.
 *
 * <p><b>Registration</b>: the Postgres backend accumulates entity classes and builds its internal
 * {@code SessionFactory} once, lazily, on the first actual repository method call (or on
 * {@link #sessionFactory(JavAIPersistenceConfig)}). Hibernate's metadata is immutable once built, so a type
 * genuinely unknown at that point can never be mapped.
 *
 * <p><b>The recommended way to make that a non-issue is to let the configuration name its own entity
 * types</b> -- {@code JavAIPersistenceConfig.Builder.entityPackages("your.domain")}, or
 * {@code entityType(...)} for individual classes. The entity set is then complete before anything can be
 * built, so repositories may be realized in any order, at any time, and no startup-ordering discipline is
 * required of the caller (OMI-214).
 *
 * <p>Without that, ordering still matters: realize every repository before invoking a method on any of them.
 * A late {@code repository(...)} call is <em>harmless</em> whenever it introduces nothing new -- the type is
 * already registered, directly or because another entity referenced it -- and fails only when it would
 * genuinely add an unknown type, in which case the error names the call that built the factory, since that
 * is the thing to move.
 */
public final class JavAIPI {

    private static final Map<JavAIPersistenceConfig, RepositoryBackend> BACKENDS = new ConcurrentHashMap<>();

    private JavAIPI() {
    }

    @SuppressWarnings("unchecked")
    public static <R extends JavAIRepository<?>> R repository(Class<R> repositoryInterface, JavAIPersistenceConfig config) {
        Class<?> entityType = resolveEntityType(repositoryInterface);
        RepositoryBackend backend = backendFor(config);
        // Validated after the backend exists but before registerEntityType touches its state: an invalid
        // repository interface must fail the same way (IllegalArgumentException) regardless of whether the
        // backend was already bootstrapped by an earlier repository() call, and -- for ordinary derived
        // finders (OMI-138) -- the check is now partly backend-specific (a nested path one store can filter
        // through, another can't), so it needs the resolved backend in hand. registerEntityType's own
        // "already built" guard is a different, backend-lifecycle error that must never mask this one.
        validateRepositoryMethods(repositoryInterface, entityType, backend);
        backend.registerEntityType(entityType);
        InvocationHandler handler = new RepositoryInvocationHandler(backend, entityType);
        return (R) Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(), new Class<?>[] {repositoryInterface}, handler);
    }

    /** Validates every method a repository interface declares beyond the base CRUD contract, at
     *  repository-creation time (never on first call). Three method families are legal, checked in order:
     *  the vector-specific {@code findNearestBy*} convention ({@link DerivedQueryMethods}); ordinary
     *  Spring-Data-style derived finders ({@link DerivedFinderQuery}, OMI-138), which must also pass the
     *  backend's own feasibility check; anything else is rejected with a clear message. */
    private static void validateRepositoryMethods(Class<?> repositoryInterface, Class<?> entityType,
            RepositoryBackend backend) {
        for (Method method : repositoryInterface.getMethods()) {
            if (method.getDeclaringClass() == JavAIRepository.class || method.getDeclaringClass() == Object.class) {
                continue; // the base CRUD contract itself, always fine
            }
            if (DerivedQueryMethods.isDerivedQueryMethod(method)) {
                DerivedQueryMethods.ParsedQuery parsed =
                        DerivedQueryMethods.parse(method, entityType); // vector convention; throws if invalid
                // Store-specific feasibility, exactly as the relational half below does it: a backend whose
                // vector index cannot narrow before it limits must say so now, not on the first call
                // (OMI-230). The shape is entirely decided by the method name, so no call is needed to know it.
                backend.validateNearestQuery(entityType, DerivedQueryMethods.shapeOnly(parsed));
                continue;
            }
            if (DerivedFinderQuery.looksLikeDerivedFinder(method)) {
                DerivedFinderQuery query = DerivedFinderQuery.parse(method, entityType); // throws if invalid
                backend.validateDerivedQuery(entityType, query); // store-specific feasibility; throws if not
                continue;
            }
            throw new IllegalArgumentException("Unsupported repository method " + method + " on repository for "
                    + entityType.getName() + " -- JavAIRepository supports the base CRUD contract, the "
                    + "findNearestBy<Field>Vector/findNearestByVector/findNearestBySummaryVector vector "
                    + "convention (optionally narrowed as ...VectorAnd<Predicate>, returning List<Ranked<T>>, "
                    + "and/or paged with a trailing Pageable/Limit), and ordinary Spring-Data-style derived "
                    + "finders (findBy/existsBy/countBy/deleteBy...); this name matches none of them. For a "
                    + "query composed at runtime rather than declared, use nearest()/nearestBy(field) instead.");
        }
    }

    private static Class<?> resolveEntityType(Class<?> repositoryInterface) {
        for (Type genericInterface : repositoryInterface.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == JavAIRepository.class
                    && parameterized.getActualTypeArguments()[0] instanceof Class<?> entityType) {
                return entityType;
            }
        }
        throw new IllegalArgumentException(repositoryInterface
                + " must directly extend JavAIRepository<SomeEntity> with a concrete type argument");
    }

    /**
     * Runs {@code body} as <b>one unit of work</b>: every repository call it makes against {@code config}'s
     * backend shares a single session and transaction, committing together at the end or rolling back
     * together if the body throws (OMI-146).
     *
     * <pre>{@code
     * JavAIPI.inTransaction(config, () -> {
     *     Channel channel = channels.save(findOrCreateChannel(platform));
     *     Participant participant = participants.save(findOrCreateParticipant(sender));
     *     entries.save(new ConversationEntry(channel, participant, text));
     * });
     * }</pre>
     *
     * <p>Without this, each of those three calls opens its own session and commits on its own, so a failure
     * on the third leaves the first two permanently committed. This is the API for callers who are not
     * running under Spring; <b>a Spring application needs nothing here</b> -- an ordinary
     * {@code @Transactional} method already establishes the unit of work, and repository calls made inside it
     * join it automatically, provided JavAI was configured with that application's own
     * {@code SessionFactory} via {@link JavAIPersistenceConfig.Builder#sessionFactory}.
     *
     * <p>Bound to the calling thread: work handed to another thread inside {@code body} is <em>not</em> part
     * of the transaction, the same restriction Spring's own transaction management carries.
     *
     * <p><b>Nesting joins, it does not nest.</b> Calling this inside another {@code inTransaction} body -- or
     * inside a Spring {@code @Transactional} method -- runs the inner body on the transaction already in
     * progress, so it commits or rolls back with the outer one and never independently. That is Spring's own
     * {@code PROPAGATION_REQUIRED} behavior, and it is what keeps two methods that each wrap their work this
     * way composable; there is no {@code REQUIRES_NEW} equivalent here in this phase.
     *
     * <p>Postgres only in this phase; the other two backends throw a message saying so rather than pretending
     * to be atomic.
     */
    public static <T> T inTransaction(JavAIPersistenceConfig config, Supplier<T> body) {
        return backendFor(config).inTransaction(body);
    }

    /** Void form of {@link #inTransaction(JavAIPersistenceConfig, Supplier)}, for a body that returns nothing. */
    public static void inTransaction(JavAIPersistenceConfig config, Runnable body) {
        backendFor(config).inTransaction(() -> {
            body.run();
            return null;
        });
    }

    /**
     * Brings every {@code @Summary} container with an outstanding recomputation up to date (OMI-255).
     *
     * <p><b>Most applications never need to call this.</b> An ordinary {@code save} already recomputes what
     * it owes once its transaction commits. This exists for the two cases where nothing else will:
     * writes made with {@link SummaryPolicy#QUEUE_ONLY}, and recomputations that were queued but whose drain
     * did not complete -- a pod killed mid-request, a database briefly unreachable. The queue is a table, so
     * that work is still there to be done and any pod may do it.
     *
     * <p>Safe to run on a schedule and safe to run concurrently from several pods: each container is
     * recomputed under an advisory lock keyed on that container, and finding nothing to do costs one query.
     *
     * <p>Postgres only, and a no-op elsewhere rather than an error -- the other backends never defer a
     * summary, so they have nothing that could be outstanding.
     */
    public static void drainPendingSummaries(JavAIPersistenceConfig config) {
        if (backendFor(config) instanceof RepositoryBackendHibernatePostgres postgres) {
            postgres.drainPendingSummaries();
        }
    }

    /**
     * The Hibernate {@link SessionFactory} this module built for {@code config} -- the same instance every
     * repository sharing that config runs on. <b>Postgres only</b>; the other two backends have no such
     * thing and throw rather than return null.
     *
     * <p><b>What this is for (OMI-160).</b> A {@code JavAIRepository} call joins a Spring
     * {@code @Transactional} unit of work only when Spring's transaction manager and JavAI hold the
     * <em>same</em> {@code SessionFactory} instance -- the lookup is an identity comparison. There are two
     * ways to arrange that, and only one of them was previously reachable:
     *
     * <ul>
     *   <li><b>Spring owns the factory.</b> Hand it to JavAI with
     *       {@link JavAIPersistenceConfig.Builder#sessionFactory}. Already supported -- but it skips the two
     *       mapping-time hooks JavAI can only apply to a factory it builds itself, so an interface-typed
     *       {@code @OneToMany JavAIList<T>} maps as a plain Hibernate bag instead of a real JavAI
     *       collection. You trade working JavAI collections for working transactions.</li>
     *   <li><b>JavAI owns the factory</b> -- this method. Keeps the JavAI collection mapping intact and
     *       still lets an application wire a transaction manager onto the very same factory.</li>
     * </ul>
     *
     * <p><b>Use {@code JpaTransactionManager}, not {@code HibernateTransactionManager}.</b> A
     * {@code SessionFactory} is a {@code jakarta.persistence.EntityManagerFactory}, so the JPA manager takes
     * it directly. {@code HibernateTransactionManager}'s {@code (SessionFactory)} constructor eagerly
     * unwraps a {@code javax.sql.DataSource} to share connections with plain JDBC, and a JavAI-built factory
     * configures Hibernate's own connection provider from raw {@code jakarta.persistence.jdbc.*} settings --
     * so it throws {@code UnknownUnwrapTypeException}. Confirmed empirically, not inferred.
     *
     * <pre>{@code
     * @Bean SessionFactory javAiSessionFactory(JavAIPersistenceConfig config) {
     *     return JavAIPI.sessionFactory(config);
     * }
     * @Bean PlatformTransactionManager transactionManager(SessionFactory factory) {
     *     return new JpaTransactionManager(factory);   // NOT HibernateTransactionManager
     * }
     * }</pre>
     *
     * <p><b>This call builds the factory, so it also freezes the entity set.</b> It is subject to the same
     * registration-before-use rule as invoking a repository method (see this class's own javadoc): call
     * {@link #repository(Class, JavAIPersistenceConfig)} for every repository interface the application
     * needs <em>before</em> calling this, or the types registered afterwards will be rejected with an
     * {@code IllegalStateException} explaining exactly that. In a Spring application that means the
     * {@code @Bean} method above should depend on whatever bean creates the repositories, not race it.
     *
     * @throws IllegalArgumentException if {@code config} names a backend other than
     *         {@link JavAIPersistenceConfig.Backend#POSTGRES}
     */
    public static SessionFactory sessionFactory(JavAIPersistenceConfig config) {
        RepositoryBackend backend = backendFor(config);
        if (!(backend instanceof RepositoryBackendHibernatePostgres postgres)) {
            throw new IllegalArgumentException("JavAIPI.sessionFactory(...) is Postgres-only -- a "
                    + config.backend() + " backend has no Hibernate SessionFactory. Spring @Transactional "
                    + "integration is likewise Postgres-only in this phase; see JavAIPI.inTransaction's javadoc.");
        }
        return postgres.sessionFactory();
    }

    private static RepositoryBackend backendFor(JavAIPersistenceConfig config) {
        return BACKENDS.computeIfAbsent(config, cfg -> switch (cfg.backend()) {
            case POSTGRES -> new RepositoryBackendHibernatePostgres(cfg);
            case NEO4J -> new RepositoryBackendNeo4j(cfg);
            case MONGODB -> new RepositoryBackendSpringDataMongo(cfg);
        });
    }
}
