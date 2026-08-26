package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
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
    /** The embedding model this search asserts, or null when it asserts none -- see {@link #inModel}. */
    private String modelId;
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

    /**
     * Asserts which embedding model this summary search is in (OMI-458).
     *
     * <pre>{@code
     * albums.nearestBySummary().inModel(Image.PIXELS_MODEL).to(reference).limit(10).ranked();
     * }</pre>
     *
     * <h2>⚠️ This is an assertion, not a selector, and the distinction is the whole of why it is optional</h2>
     *
     * Every backend already resolves <em>which</em> storage answers from {@code reference.modelId()}, so
     * {@link #to} alone selects the right table or property and always has -- a summary search in a model
     * that only ever arrives through {@code @ExternalVector} works with no call to this method at all.
     * Ranking across two embedding spaces is therefore not something this prevents; it cannot happen, because
     * the index is derived from the reference rather than chosen beside it.
     *
     * <p>What it prevents is a <b>caller's</b> slip. A container carrying both a {@code @Vectorize} field and
     * an {@code @ExternalVector} has two coherent summaries, and
     *
     * <pre>{@code
     * albums.nearestBySummary().to(album.summaryVector());          // the text one
     * albums.nearestBySummary().to(album.summaryVector(PIXELS));    // the pixel one
     * }</pre>
     *
     * differ by one token. Both compile, both run, and both return sensible hits against their own storage --
     * so passing the wrong one answers the <em>other</em> of the container's two questions with nothing to
     * notice. Naming the model here turns that into a refusal on the call. It buys legibility at the call
     * site and a checked assumption; it buys no capability, which is why nothing requires it.
     *
     * <p><b>Meaningful only for a summary search.</b> A field-grain search names the vector, and an
     * {@code @ExternalVector}'s model is fixed by its own declaration, so there is exactly one model it could
     * be in; the combined vector and the concatenated text vector exist in the configured provider's model
     * and no other. Called on any of those this refuses, rather than accepting a qualifier that does nothing
     * and inviting the belief that it does something.
     *
     * @param modelId the embedding model to rank in, as {@code EmbeddingVector.modelId()} reports it
     * @throws IllegalArgumentException if blank, if this is not a summary search, or -- on execution -- if
     *                                  the reference vector is from a different model
     */
    public NearestQuery<T> inModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("inModel(...) needs an embedding model id -- the same string "
                    + "EmbeddingVector.modelId() reports, e.g. \"siglip2-so400m-p16-384/pp1\". Omit the call "
                    + "entirely to search whichever model the reference vector came from.");
        }
        if (kind != DerivedQueryMethods.Kind.SUMMARY) {
            throw new IllegalArgumentException("inModel(...) is meaningful only for a summary search, and "
                    + "this one is " + describeKind() + ". A summary is the one vector an object has several "
                    + "of, one per model, so it is the one where naming the model says something the call "
                    + "does not already say. Drop the inModel(...) call -- the reference vector already "
                    + "selects the storage.");
        }
        this.modelId = modelId;
        return this;
    }

    /** How this search reads in an error message -- the grain, in the caller's own vocabulary. */
    private String describeKind() {
        return switch (kind) {
            case SUMMARY -> "a summary search";
            case CONCATENATED_TEXT -> "a concatenated-text search, and concatenated text is one embedding of "
                    + "one assembled string, so it exists in the configured provider's model and no other";
            case COMBINED -> "a search of the object's own combined vector, which is stored in the configured "
                    + "provider's model";
            case FIELD -> "a search of '" + fieldName + "', which names one vector -- a @Vectorize field is "
                    + "in the configured provider's model and an @ExternalVector is in the one its own "
                    + "declaration fixes";
        };
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
        NearestSpec spec = new NearestSpec(kind, fieldName, reference, limit, offset,
                List.copyOf(predicate), modelId);
        // Checked here rather than in the backend so it fails on the builder that made the mistake, the
        // same discipline resolve(...) follows for a bad property name (OMI-458).
        spec.requireModelAgreement();
        return spec;
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

    private NearestQuery<T> addAnyDiscriminator(PropertyPath property, Part.Type type, List<Object> arguments) {
        orGroups.get(orGroups.size() - 1)
                .add(new DerivedFinderQuery.BoundPart(property, type, false, arguments, true));
        return this;
    }

    /** Fails on the call that made the mistake, matching {@link #resolve}'s own discipline -- the builder's
     *  counterpart to the creation-time check the method-name idiom gets. */
    private void requireAnyField(PropertyPath property) {
        String dotPath = property.toDotPath();
        if (dotPath.contains(".")
                || !EntityReflection.isAny(EntityReflection.findField(entityType, dotPath))) {
            throw new IllegalArgumentException("ofType(...) narrows by an @Any field's discriminator, but '"
                    + dotPath + "' on " + entityType.getName() + " is not an @Any field. Known @Any fields: "
                    + EntityReflection.anyFields(entityType).stream().map(java.lang.reflect.Field::getName).toList()
                    + ".");
        }
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

        /**
         * Narrows to rows whose {@code @Any} target is of {@code targetType}, whichever instance it is --
         * the builder's spelling of the {@code OfType} keyword (OMI-407).
         *
         * @throws IllegalArgumentException if this condition's property is not an {@code @Any} field
         */
        public NearestQuery<T> ofType(Class<?> targetType) {
            return anyDiscriminator(Part.Type.SIMPLE_PROPERTY, List.of(targetType));
        }

        /** {@link #ofType} against several target types at once. */
        public NearestQuery<T> ofTypeIn(Collection<Class<?>> targetTypes) {
            return anyDiscriminator(Part.Type.IN, List.of(List.copyOf(targetTypes)));
        }

        private NearestQuery<T> anyDiscriminator(Part.Type type, List<Object> arguments) {
            query.requireAnyField(property);
            return query.addAnyDiscriminator(property, type, arguments);
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
