package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code InvocationHandler} behind every proxy {@link JavAIPI#repository(Class)} creates. Dispatches
 * the base CRUD contract straight to {@link RepositoryBackend} -- including {@code reindexAll}, expressed
 * purely as a {@code findAll()} + {@code save(...)} loop over the existing backend methods, needing no
 * backend-specific support of its own; anything named {@code findNearestBy*} is parsed once (cached per
 * {@link Method}, since {@link JavAIPI#repository(Class)} already validated it at creation time) via
 * {@link DerivedQueryMethods} and dispatched to whichever backend method matches its
 * {@link DerivedQueryMethods.Kind}.
 */
final class RepositoryInvocationHandler implements InvocationHandler {

    private final RepositoryBackend backend;
    private final Class<?> entityType;
    private final Map<Method, DerivedQueryMethods.ParsedQuery> parsedQueries = new ConcurrentHashMap<>();
    private final Map<Method, DerivedFinderQuery> derivedFinders = new ConcurrentHashMap<>();

    RepositoryInvocationHandler(RepositoryBackend backend, Class<?> entityType) {
        this.backend = backend;
        this.entityType = entityType;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "save":
                return backend.save(entityType, args[0]);
            case "findById":
                return backend.findById(entityType, (UUID) args[0]);
            case "findAll":
                return backend.findAll(entityType);
            case "deleteById":
                backend.deleteById(entityType, (UUID) args[0]);
                return null;
            case "reindexAll":
                backend.reindexAll();
                return null;
            case "reindex":
                backend.reindex(entityType);
                return null;
            case "toString":
                return "JavAIRepository<" + entityType.getSimpleName() + ">";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                break;
        }
        if (DerivedQueryMethods.isDerivedQueryMethod(method)) {
            DerivedQueryMethods.ParsedQuery parsed =
                    parsedQueries.computeIfAbsent(method, m -> DerivedQueryMethods.parse(m, entityType));
            EmbeddingVector reference = (EmbeddingVector) args[0];
            int limit = (Integer) args[1];
            return switch (parsed.kind()) {
                case FIELD, COMBINED -> backend.findNearestByFieldVector(entityType, parsed.fieldName(), reference, limit);
                case SUMMARY -> backend.findNearestBySummaryVector(entityType, reference, limit);
            };
        }
        // Ordinary Spring-Data-style derived finder (OMI-138) -- checked after findNearestBy* since the two
        // prefixes are disjoint (findNearestBy never startsWith findBy). Parsing was already validated at
        // repository-creation time in JavAIPI; cached per Method here exactly like the vector queries above.
        if (DerivedFinderQuery.looksLikeDerivedFinder(method)) {
            DerivedFinderQuery query =
                    derivedFinders.computeIfAbsent(method, m -> DerivedFinderQuery.parse(m, entityType));
            return query.execute(backend, entityType, args);
        }
        throw new UnsupportedOperationException("Unsupported repository method " + method);
    }
}
