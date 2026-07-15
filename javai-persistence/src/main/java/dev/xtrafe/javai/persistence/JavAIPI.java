package dev.xtrafe.javai.persistence;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * {@code findNearestBySummaryVector} ({@code summaryVector()}). Anything else -- including any other
 * Spring-Data-style derived query -- is rejected here, at repository-creation time, not on first call.
 *
 * <p><b>Registration-before-use</b>: call {@link #repository(Class, JavAIPersistenceConfig)} for every
 * repository interface an application needs *before* invoking methods on any of them. The Postgres
 * backend's internal {@code SessionFactory} accumulates entity classes across these calls and is built,
 * once, lazily, on first actual method invocation -- Hibernate's metadata is immutable once built, so an
 * entity type registered only after that point would never be picked up.
 */
public final class JavAIPI {

    private static final Map<JavAIPersistenceConfig, RepositoryBackend> BACKENDS = new ConcurrentHashMap<>();

    private JavAIPI() {
    }

    @SuppressWarnings("unchecked")
    public static <R extends JavAIRepository<?>> R repository(Class<R> repositoryInterface, JavAIPersistenceConfig config) {
        Class<?> entityType = resolveEntityType(repositoryInterface);
        // Validated before touching any backend state, deliberately: this check must fail the same way
        // (IllegalArgumentException) regardless of whether the backend has already been bootstrapped by an
        // earlier repository() call -- registerEntityType's own "already built" guard is a different,
        // backend-lifecycle error and must never mask an invalid repository interface.
        validateDerivedQueryMethods(repositoryInterface, entityType);
        RepositoryBackend backend = backendFor(config);
        backend.registerEntityType(entityType);
        InvocationHandler handler = new RepositoryInvocationHandler(backend, entityType);
        return (R) Proxy.newProxyInstance(
                repositoryInterface.getClassLoader(), new Class<?>[] {repositoryInterface}, handler);
    }

    private static void validateDerivedQueryMethods(Class<?> repositoryInterface, Class<?> entityType) {
        for (Method method : repositoryInterface.getMethods()) {
            if (method.getDeclaringClass() == JavAIRepository.class || method.getDeclaringClass() == Object.class) {
                continue; // the base CRUD contract itself, always fine
            }
            if (!DerivedQueryMethods.isDerivedQueryMethod(method)) {
                throw new IllegalArgumentException("Unsupported repository method " + method + " on repository for "
                        + entityType.getName() + " -- JavAIRepository only supports the base CRUD contract plus "
                        + "findNearestBy<Field>Vector/findNearestByVector/findNearestBySummaryVector(EmbeddingVector, int); "
                        + "arbitrary derived queries aren't part of Persistence Bridge's contract.");
            }
            DerivedQueryMethods.parse(method, entityType); // throws with a clear message if invalid
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

    private static RepositoryBackend backendFor(JavAIPersistenceConfig config) {
        return BACKENDS.computeIfAbsent(config, cfg -> switch (cfg.backend()) {
            case POSTGRES -> new RepositoryBackendHibernatePostgres(cfg);
            case NEO4J -> new RepositoryBackendNeo4j(cfg);
            case MONGODB -> new RepositoryBackendSpringDataMongo(cfg);
        });
    }
}
