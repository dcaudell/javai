package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;
import org.springframework.data.core.PropertyPath;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.repository.query.parser.Part;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * A vector search assembled call-by-call, for the narrowing, ranking and paging the
 * {@code findNearestBy…Vector} method-name convention cannot express (OMI-230).
 *
 * <pre>{@code
 * List<Ranked<MediaSocialDetails>> hits = details.nearestBy("caption")
 *         .to(reference)
 *         .where("kind").in(Kind.IMAGE, Kind.SHORT)
 *         .and("published").isTrue()
 *         .offset(20)
 *         .limit(20)
 *         .ranked();
 * }</pre>
 *
 * <h2>Why this exists alongside the method-name convention</h2>
 *
 * The convention stays the better choice whenever it fits: it is validated at repository-creation time
 * rather than on the call, and the query is visible in the interface. This is for what a method name cannot
 * carry -- a predicate composed at runtime, a page offset chosen by a caller, a shape not worth minting a
 * method for. The two are not parallel implementations: {@link DerivedQueryMethods} compiles a parsed method
 * name into exactly the {@link NearestSpec} this builder produces, so both idioms reach the same backend
 * code and cannot answer the same question differently.
 *
 * <h2>Shape</h2>
 *
 * Mutable and single-use -- every method returns {@code this}, and a terminal ({@link #results()} or
 * {@link #ranked()}) may be called once. Predicates are OR-of-ANDs, the same shape a derived finder's
 * method name parses to: {@link #where}/{@link #and} extend the current AND-group, {@link #or} starts a new
 * one. Property names are resolved against the entity type as they are added, so a typo fails on the call
 * that made it rather than at execution.
 *
 * <p><b>Narrowing is applied before the limit</b>, not after -- see {@link NearestSpec}. A backend whose
 * vector index cannot do that refuses the query rather than approximating it, so a narrowed search either
 * answers exactly or says it cannot.
 */
public final class NearestQuery<T> {

    private final RepositoryBackend backend;
    private final Class<?> entityType;
    private final DerivedQueryMethods.Kind kind;
    private final String fieldName;

    private final List<List<DerivedFinderQuery.BoundPart>> orGroups = new ArrayList<>();
    private EmbeddingVector reference;
    private Integer limit;
    private int offset;
    private boolean spent;

    NearestQuery(RepositoryBackend backend, Class<?> entityType, DerivedQueryMethods.Kind kind, String fieldName) {
        this.backend = backend;
        this.entityType = entityType;
        this.kind = kind;
        this.fieldName = fieldName;
        this.orGroups.add(new ArrayList<>());
    }

    /** The vector to rank against. Required. */
    public NearestQuery<T> to(EmbeddingVector reference) {
        if (reference == null) {
            throw new IllegalArgumentException("Reference vector must not be null -- there is nothing to rank against.");
        }
        this.reference = reference;
        return this;
    }

    /** Maximum hits to return, applied <em>after</em> any narrowing. Required: an unbounded vector search
     *  over a whole store is never what a caller means, and defaulting to one silently would be worse than
     *  asking. */
    public NearestQuery<T> limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, but was " + limit + ".");
        }
        this.limit = limit;
        return this;
    }

    /** How many of the nearest matches to skip -- the page offset the method-name convention has no room
     *  for. Exact rather than approximate: ranking is total, so skipping the head of it is well-defined. */
    public NearestQuery<T> offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, but was " + offset + ".");
        }
        this.offset = offset;
        return this;
    }

    /** Starts the predicate on {@code property} (a field name, or a dot-path such as
     *  {@code "profile.displayName"}). */
    public Condition<T> where(String property) {
        return new Condition<>(this, resolve(property));
    }

    /** AND-s a further condition onto the current group. */
    public Condition<T> and(String property) {
        return where(property);
    }

    /** Starts a new OR-ed group: everything added after this is OR-ed against everything before it. */
    public Condition<T> or(String property) {
        orGroups.add(new ArrayList<>());
        return where(property);
    }

    /** The hits themselves, nearest first. */
    public List<T> results() {
        List<Ranked<T>> ranked = ranked();
        List<T> out = new ArrayList<>(ranked.size());
        for (Ranked<T> hit : ranked) {
            out.add(hit.entity());
        }
        return out;
    }

    /** The hits with the similarity they were ranked on, nearest first -- see {@link Ranked}. */
    @SuppressWarnings("unchecked")
    public List<Ranked<T>> ranked() {
        NearestSpec spec = build();
        backend.validateNearestQuery(entityType, spec);
        return (List<Ranked<T>>) (List<?>) backend.findNearest(entityType, spec);
    }

    private NearestSpec build() {
        if (spent) {
            throw new IllegalStateException("This NearestQuery has already been executed. Build a new one from "
                    + "the repository rather than re-running a spent builder -- reusing one would quietly share "
                    + "predicate state between two searches.");
        }
        if (reference == null) {
            throw new IllegalStateException("No reference vector: call to(EmbeddingVector) before executing.");
        }
        if (limit == null) {
            throw new IllegalStateException("No limit: call limit(int) before executing.");
        }
        spent = true;
        List<List<DerivedFinderQuery.BoundPart>> predicate = new ArrayList<>();
        for (List<DerivedFinderQuery.BoundPart> group : orGroups) {
            if (!group.isEmpty()) {
                predicate.add(List.copyOf(group));
            }
        }
        return new NearestSpec(kind, fieldName, reference, limit, offset, List.copyOf(predicate));
    }

    private PropertyPath resolve(String property) {
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("Property name must not be blank.");
        }
        try {
            return PropertyPath.from(property, entityType);
        } catch (PropertyReferenceException e) {
            throw new IllegalArgumentException("No property '" + property + "' on " + entityType.getName()
                    + " to narrow this vector search by -- " + e.getMessage(), e);
        }
    }

    private NearestQuery<T> add(PropertyPath property, Part.Type type, boolean ignoreCase, List<Object> arguments) {
        orGroups.get(orGroups.size() - 1)
                .add(new DerivedFinderQuery.BoundPart(property, type, ignoreCase, arguments));
        return this;
    }

    /**
     * One condition mid-construction: the property is chosen, the operator is not yet. Every method here
     * returns to the {@link NearestQuery} so the chain continues.
     *
     * <p>The operator vocabulary is deliberately the same one the method-name convention exposes, and maps
     * onto the very same {@link Part.Type} constants -- so a predicate expressible in a method name is
     * expressible here, and both are translated by the backend's one existing predicate translator rather
     * than a second one written for builders.
     */
    public static final class Condition<T> {

        private final NearestQuery<T> query;
        private final PropertyPath property;
        private boolean ignoreCase;

        private Condition(NearestQuery<T> query, PropertyPath property) {
            this.query = query;
            this.property = property;
        }

        /** Compares case-insensitively, where the backend and the property's type support it. */
        public Condition<T> ignoringCase() {
            this.ignoreCase = true;
            return this;
        }

        public NearestQuery<T> is(Object value) {
            return finish(Part.Type.SIMPLE_PROPERTY, value);
        }

        public NearestQuery<T> isNot(Object value) {
            return finish(Part.Type.NEGATING_SIMPLE_PROPERTY, value);
        }

        public NearestQuery<T> in(Object... values) {
            return in(Arrays.asList(values));
        }

        public NearestQuery<T> in(Collection<?> values) {
            return finish(Part.Type.IN, List.copyOf(values));
        }

        public NearestQuery<T> notIn(Collection<?> values) {
            return finish(Part.Type.NOT_IN, List.copyOf(values));
        }

        public NearestQuery<T> greaterThan(Object value) {
            return finish(Part.Type.GREATER_THAN, value);
        }

        public NearestQuery<T> greaterThanOrEqual(Object value) {
            return finish(Part.Type.GREATER_THAN_EQUAL, value);
        }

        public NearestQuery<T> lessThan(Object value) {
            return finish(Part.Type.LESS_THAN, value);
        }

        public NearestQuery<T> lessThanOrEqual(Object value) {
            return finish(Part.Type.LESS_THAN_EQUAL, value);
        }

        public NearestQuery<T> between(Object lower, Object upper) {
            return query.add(property, Part.Type.BETWEEN, ignoreCase, Arrays.asList(lower, upper));
        }

        public NearestQuery<T> like(String pattern) {
            return finish(Part.Type.LIKE, pattern);
        }

        public NearestQuery<T> containing(String fragment) {
            return finish(Part.Type.CONTAINING, fragment);
        }

        public NearestQuery<T> startingWith(String prefix) {
            return finish(Part.Type.STARTING_WITH, prefix);
        }

        public NearestQuery<T> endingWith(String suffix) {
            return finish(Part.Type.ENDING_WITH, suffix);
        }

        public NearestQuery<T> isNull() {
            return query.add(property, Part.Type.IS_NULL, ignoreCase, List.of());
        }

        public NearestQuery<T> isNotNull() {
            return query.add(property, Part.Type.IS_NOT_NULL, ignoreCase, List.of());
        }

        public NearestQuery<T> isTrue() {
            return query.add(property, Part.Type.TRUE, ignoreCase, List.of());
        }

        public NearestQuery<T> isFalse() {
            return query.add(property, Part.Type.FALSE, ignoreCase, List.of());
        }

        private NearestQuery<T> finish(Part.Type type, Object value) {
            List<Object> arguments = new ArrayList<>(1);
            arguments.add(value); // nullable on purpose: `is(null)` is a legitimate way to say IS NULL
            return query.add(property, type, ignoreCase, arguments);
        }
    }
}
