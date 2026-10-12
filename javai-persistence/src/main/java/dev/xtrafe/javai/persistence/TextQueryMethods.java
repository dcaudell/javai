package dev.xtrafe.javai.persistence;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

/**
 * The method-name form of a full-text search (OMI-642), as {@link DerivedQueryMethods} is of a vector search:
 *
 * <pre>{@code
 * List<Passage> findMatchingByText(String query, int limit);                                 // terms
 * List<Ranked<Passage>> findMatchingByTextAndRulesetIdIn(String query, Collection<UUID> ids, Limit limit);
 * List<Codex> findSimilarByName(String name, int limit);                                     // fuzzy
 * }</pre>
 *
 * <p>{@code <Field>} names a {@code @FullText} field; {@code findSimilarBy} needs one declared {@code fuzzy}. The
 * narrowing tail after {@code And} is parsed by the same {@link PartTree} the {@code findBy…} finders use, and the
 * signature follows the vector form's: the query text first, then an optional {@code int limit}, the predicate's
 * arguments, and an optional trailing {@link Pageable} or {@link Limit}. Everything compiles to the {@link TextSpec}
 * the {@link TextQuery} builder produces, so the two forms cannot answer differently. Invalid shapes fail when the
 * repository is created.
 */
final class TextQueryMethods {

    private static final String MATCHING = "findMatchingBy";
    private static final String SIMILAR = "findSimilarBy";

    /** A parsed full-text query method; see {@link DerivedQueryMethods.ParsedQuery} for the index fields. */
    record ParsedText(FullTextFields.Indexed field, boolean fuzzy, PartTree predicate, int limitParamIndex,
            int firstBindable, int pageableIndex, int limitObjectIndex, boolean ranked) {
    }

    private TextQueryMethods() {
    }

    static boolean isTextQueryMethod(Method method) {
        return method.getName().startsWith(MATCHING) || method.getName().startsWith(SIMILAR);
    }

    static ParsedText parse(Method method, Class<?> entityType) {
        String name = method.getName();
        boolean fuzzy = name.startsWith(SIMILAR);
        String rest = name.substring(fuzzy ? SIMILAR.length() : MATCHING.length());
        FullTextFields.Indexed field = null;
        String tail = null;
        for (FullTextFields.Indexed candidate : FullTextFields.of(entityType)) {
            String capitalized = Character.toUpperCase(candidate.name().charAt(0)) + candidate.name().substring(1);
            String after = rest.startsWith(capitalized) ? rest.substring(capitalized.length()) : null;
            if (after != null && (after.isEmpty() || after.startsWith("And"))
                    && (field == null || candidate.name().length() > field.name().length())) {
                field = candidate;
                tail = after;
            }
        }
        if (field == null) {
            throw new IllegalArgumentException(method + " does not name a @FullText field of " + entityType.getName()
                    + " -- known: " + FullTextFields.of(entityType).stream().map(FullTextFields.Indexed::name).toList());
        }
        if (fuzzy && !field.fuzzy()) {
            throw new IllegalArgumentException(method + " searches '" + field.name() + "' fuzzily, but it is not "
                    + "declared @FullText(fuzzy = true).");
        }
        PartTree predicate = DerivedQueryMethods.parsePredicateTail(method, entityType, tail);
        return resolveSignature(method, entityType, field, fuzzy, predicate);
    }

    private static ParsedText resolveSignature(Method method, Class<?> entityType, FullTextFields.Indexed field,
            boolean fuzzy, PartTree predicate) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0 || params[0] != String.class || !List.class.isAssignableFrom(method.getReturnType())) {
            throw badShape(method, entityType);
        }
        int pageableIndex = -1;
        int limitObjectIndex = -1;
        for (int i = 1; i < params.length; i++) {
            if (Pageable.class.isAssignableFrom(params[i])) {
                pageableIndex = i;
            } else if (Limit.class.isAssignableFrom(params[i])) {
                limitObjectIndex = i;
            }
        }
        int trailingStart = params.length;
        for (int index : new int[] {pageableIndex, limitObjectIndex}) {
            if (index >= 0) {
                trailingStart = Math.min(trailingStart, index);
            }
        }
        int special = (pageableIndex >= 0 ? 1 : 0) + (limitObjectIndex >= 0 ? 1 : 0);
        if (trailingStart + special != params.length) {
            throw new IllegalArgumentException(method + " must declare any Pageable/Limit parameter last.");
        }
        int limitParamIndex = -1;
        int firstBindable = 1;
        if (params.length > 1 && (params[1] == int.class || params[1] == Integer.class)) {
            limitParamIndex = 1;
            firstBindable = 2;
        } else if (pageableIndex < 0 && limitObjectIndex < 0) {
            throw badShape(method, entityType); // a search with no bound at all is never what a caller means
        }
        int demanded = 0;
        if (predicate != null) {
            for (PartTree.OrPart orPart : predicate) {
                for (Part part : orPart) {
                    demanded += part.getNumberOfArguments();
                }
            }
        }
        if (trailingStart - firstBindable != demanded) {
            throw new IllegalArgumentException(method + " declares " + (trailingStart - firstBindable)
                    + " argument(s) for its narrowing predicate, which needs " + demanded + ".");
        }
        return new ParsedText(field, fuzzy, predicate, limitParamIndex, firstBindable, pageableIndex, limitObjectIndex,
                DerivedQueryMethods.returnsRanked(method));
    }

    /** Binds one call's arguments into the search this parse describes. */
    static TextSpec toSpec(ParsedText parsed, Object[] args) {
        int limit = parsed.limitParamIndex() >= 0 ? (Integer) args[parsed.limitParamIndex()] : Integer.MAX_VALUE;
        int offset = 0;
        if (parsed.pageableIndex() >= 0 && args[parsed.pageableIndex()] instanceof Pageable pageable
                && pageable.isPaged()) {
            offset = Math.toIntExact(pageable.getOffset());
            limit = pageable.getPageSize();
        }
        if (parsed.limitObjectIndex() >= 0 && args[parsed.limitObjectIndex()] instanceof Limit explicit
                && explicit.isLimited()) {
            limit = explicit.max();
        }
        List<List<DerivedFinderQuery.BoundPart>> groups = new ArrayList<>();
        if (parsed.predicate() != null) {
            int cursor = parsed.firstBindable();
            for (PartTree.OrPart orPart : parsed.predicate()) {
                List<DerivedFinderQuery.BoundPart> group = new ArrayList<>();
                for (Part part : orPart) {
                    List<Object> partArgs = new ArrayList<>(part.getNumberOfArguments());
                    for (int i = 0; i < part.getNumberOfArguments(); i++) {
                        partArgs.add(args[cursor++]);
                    }
                    group.add(new DerivedFinderQuery.BoundPart(part.getProperty(), part.getType(),
                            part.shouldIgnoreCase() != Part.IgnoreCaseType.NEVER, partArgs));
                }
                groups.add(List.copyOf(group));
            }
        }
        return new TextSpec(parsed.field(), (String) args[0], parsed.fuzzy(), limit, offset, List.copyOf(groups));
    }

    private static IllegalArgumentException badShape(Method method, Class<?> entityType) {
        return new IllegalArgumentException(method + " on repository for " + entityType.getName()
                + " must have the shape findMatchingBy<Field>(String query, int limit) or findSimilarBy<Field>(…):"
                + " List<T> or List<Ranked<T>>, optionally narrowed (…And<Predicate>(query, limit, args…)) and/or"
                + " paged by a trailing Pageable/Limit. The int limit may be omitted only when one of those supplies"
                + " it.");
    }
}
