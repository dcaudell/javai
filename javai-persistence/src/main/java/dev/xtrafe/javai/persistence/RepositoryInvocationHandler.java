package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code InvocationHandler} behind every proxy {@link JavAIPI#repository(Class, JavAIPersistenceConfig)} creates. Dispatches
 * the base CRUD contract straight to {@link RepositoryBackend} -- including {@code reindexAll}, expressed
 * purely as a {@code findAll()} + {@code save(...)} loop over the existing backend methods, needing no
 * backend-specific support of its own; anything named {@code findNearestBy*} is parsed once (cached per
 * {@link Method}, since {@link JavAIPI#repository(Class, JavAIPersistenceConfig)} already validated it at
 * creation time) via {@link DerivedQueryMethods}, bound into a {@link NearestSpec}, and handed to the one
 * {@link RepositoryBackend#findNearest} the builder idiom reaches too.
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
                // Two arguments means the SummaryPolicy overload (OMI-255); one means the original method,
                // which is exactly that overload's RECOMPUTE_AFTER_COMMIT default.
                return args.length == 2
                        ? backend.save(entityType, args[0], (SummaryPolicy) args[1])
                        : backend.save(entityType, args[0]);
            case "saveAll":
                return backend.saveAll(entityType, materialize((Iterable<?>) args[0]), args.length == 2
                        ? (SummaryPolicy) args[1]
                        : SummaryPolicy.RECOMPUTE_AFTER_COMMIT);
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
            case "supplyVector":
                return backend.supplyVector(entityType, (UUID) args[0], (String) args[1],
                        (EmbeddingVector) args[2], (String) args[3]);
            case "findPendingVector":
                return backend.findPendingVector(entityType, (String) args[0], (Integer) args[1]);
            // The builder idiom (OMI-230). Declared on JavAIRepository itself, so these names are matched
            // here before the findNearestBy* convention below ever sees them -- and are deliberately not
            // spelled findNearestBy*, so the two idioms cannot collide on a name in the first place.
            case "nearest":
                return newNearestQuery(DerivedQueryMethods.Kind.COMBINED, RepositoryBackend.COMBINED_VECTOR_FIELD);
            case "nearestBy":
                return newNearestQuery(
                        DerivedQueryMethods.Kind.FIELD, DerivedQueryMethods.requireVectorizeField(
                                entityType, (String) args[0]));
            case "nearestBySummary":
                return newNearestQuery(DerivedQueryMethods.Kind.SUMMARY, null);
            case "nearestByConcatenatedText":
                DerivedQueryMethods.requireConcatenationParticipant(entityType);
                return newNearestQuery(DerivedQueryMethods.Kind.CONCATENATED_TEXT, null);
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
            List<Ranked<Object>> hits =
                    backend.findNearest(entityType, DerivedQueryMethods.toSpec(parsed, args));
            // Unwrapping here, not in the backend, is what lets one backend method serve both return shapes
            // -- and the same reason the builder's results()/ranked() pair needs no second backend call.
            if (parsed.ranked()) {
                return hits;
            }
            List<Object> entities = new ArrayList<>(hits.size());
            for (Ranked<Object> hit : hits) {
                entities.add(hit.entity());
            }
            return entities;
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

    /** {@code saveAll}'s parameter is an {@link Iterable}, which may be consumable only once -- and the
     *  backend both warms it and then iterates it again to save. Materialized here so no backend has to
     *  remember that, and so the one that does it wrong cannot silently save an empty batch. */
    private static List<Object> materialize(Iterable<?> entities) {
        List<Object> materialized = new ArrayList<>();
        for (Object entity : entities) {
            materialized.add(entity);
        }
        return materialized;
    }

    private NearestQuery<Object> newNearestQuery(DerivedQueryMethods.Kind kind, String fieldName) {
        return new NearestQuery<>(backend, entityType, kind, fieldName);
    }
}
