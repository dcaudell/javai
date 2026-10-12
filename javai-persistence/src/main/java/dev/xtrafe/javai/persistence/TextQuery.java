package dev.xtrafe.javai.persistence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import dev.xtrafe.javai.vector.Ranked;
import org.springframework.data.core.PropertyPath;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.repository.query.parser.Part;

/**
 * A ranked full-text search of one {@code @FullText} field, assembled call-by-call (OMI-642).
 *
 * <pre>{@code
 * List<Ranked<Passage>> hits = passages.matching("text")
 *         .query("grappling \"called shot\"")
 *         .where("rulesetId").in(rulesetIds)
 *         .and("audience").in(Audience.DM, Audience.BOTH)
 *         .limit(10)
 *         .ranked();
 *
 * List<Codex> named = codex.matching("name").query("Glabrezu").fuzzy().limit(5).results();
 * }</pre>
 *
 * <p>The narrowing is applied before the limit, as {@link NearestQuery}'s is, and by the same machinery, so a predicate
 * means here exactly what it means in a {@code findBy…} finder. A hit's {@link Ranked#similarity()} is the store's own
 * text score (on Postgres, {@code ts_rank_cd}, or trigram {@code word_similarity} for {@link #fuzzy()}): higher is
 * better, but it is not a cosine and not comparable across searches. To fuse with a vector search, use
 * {@link NearestQuery#alsoMatching}.
 *
 * <p>Mutable and single-use, as {@link NearestQuery} is. A backend with no full-text search refuses the terminal.
 */
public final class TextQuery<T> {

    private final RepositoryBackend backend;
    private final Class<?> entityType;
    private final FullTextFields.Indexed field;
    private final List<List<DerivedFinderQuery.BoundPart>> orGroups = new ArrayList<>();
    private String text;
    private boolean fuzzy;
    private Integer limit;
    private int offset;
    private boolean spent;

    TextQuery(RepositoryBackend backend, Class<?> entityType, FullTextFields.Indexed field) {
        this.backend = backend;
        this.entityType = entityType;
        this.field = field;
        this.orGroups.add(new ArrayList<>());
    }

    /** What to search for. Required. */
    public TextQuery<T> query(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Nothing to search for: the query text is blank.");
        }
        this.text = text;
        return this;
    }

    /**
     * By trigram similarity instead of by terms: the field's values nearest the query as strings, so "Glabreuz" finds
     * "Glabrezu". Only on a field declared {@code @FullText(fuzzy = true)}.
     */
    public TextQuery<T> fuzzy() {
        if (!field.fuzzy()) {
            throw new IllegalArgumentException("'" + field.name() + "' on " + entityType.getName()
                    + " is not trigram-indexed -- declare it @FullText(fuzzy = true) to search it fuzzily.");
        }
        this.fuzzy = true;
        return this;
    }

    public TextQuery<T> limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, but was " + limit + ".");
        }
        this.limit = limit;
        return this;
    }

    public TextQuery<T> offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, but was " + offset + ".");
        }
        this.offset = offset;
        return this;
    }

    public Condition<T> where(String property) {
        return new Condition<>(this, resolve(property));
    }

    public Condition<T> and(String property) {
        return where(property);
    }

    public Condition<T> or(String property) {
        orGroups.add(new ArrayList<>());
        return where(property);
    }

    public List<T> results() {
        List<T> out = new ArrayList<>();
        for (Ranked<T> hit : ranked()) {
            out.add(hit.entity());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Ranked<T>> ranked() {
        if (spent) {
            throw new IllegalStateException("This TextQuery has already been executed; build a new one.");
        }
        if (text == null) {
            throw new IllegalStateException("No query text: call query(String) before executing.");
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
        TextSpec spec = new TextSpec(field, text, fuzzy, limit, offset, List.copyOf(predicate));
        return (List<Ranked<T>>) (List<?>) backend.findMatching(entityType, spec);
    }

    private PropertyPath resolve(String property) {
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("Property name must not be blank.");
        }
        try {
            return PropertyPath.from(property, entityType);
        } catch (PropertyReferenceException e) {
            throw new IllegalArgumentException("No property '" + property + "' on " + entityType.getName()
                    + " to narrow this text search by -- " + e.getMessage(), e);
        }
    }

    private TextQuery<T> add(PropertyPath property, Part.Type type, boolean ignoreCase, List<Object> arguments) {
        orGroups.get(orGroups.size() - 1).add(new DerivedFinderQuery.BoundPart(property, type, ignoreCase, arguments));
        return this;
    }

    /** One narrowing condition; the operators of {@link NearestQuery.Condition}'s that a text search needs. */
    public static final class Condition<T> {

        private final TextQuery<T> query;
        private final PropertyPath property;
        private boolean ignoreCase;

        private Condition(TextQuery<T> query, PropertyPath property) {
            this.query = query;
            this.property = property;
        }

        public Condition<T> ignoringCase() {
            this.ignoreCase = true;
            return this;
        }

        public TextQuery<T> is(Object value) {
            return finish(Part.Type.SIMPLE_PROPERTY, value);
        }

        public TextQuery<T> isNot(Object value) {
            return finish(Part.Type.NEGATING_SIMPLE_PROPERTY, value);
        }

        public TextQuery<T> in(Object... values) {
            return in(Arrays.asList(values));
        }

        public TextQuery<T> in(Collection<?> values) {
            return finish(Part.Type.IN, List.copyOf(values));
        }

        public TextQuery<T> notIn(Collection<?> values) {
            return finish(Part.Type.NOT_IN, List.copyOf(values));
        }

        public TextQuery<T> greaterThanOrEqual(Object value) {
            return finish(Part.Type.GREATER_THAN_EQUAL, value);
        }

        public TextQuery<T> lessThanOrEqual(Object value) {
            return finish(Part.Type.LESS_THAN_EQUAL, value);
        }

        public TextQuery<T> isNull() {
            return query.add(property, Part.Type.IS_NULL, ignoreCase, List.of());
        }

        public TextQuery<T> isNotNull() {
            return query.add(property, Part.Type.IS_NOT_NULL, ignoreCase, List.of());
        }

        public TextQuery<T> isTrue() {
            return query.add(property, Part.Type.TRUE, ignoreCase, List.of());
        }

        public TextQuery<T> isFalse() {
            return query.add(property, Part.Type.FALSE, ignoreCase, List.of());
        }

        private TextQuery<T> finish(Part.Type type, Object value) {
            List<Object> arguments = new ArrayList<>(1);
            arguments.add(value);
            return query.add(property, type, ignoreCase, arguments);
        }
    }
}
