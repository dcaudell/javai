package dev.xtrafe.javai.persistence;

import java.util.List;

/**
 * One fully-resolved full-text search, as a {@link RepositoryBackend} receives it (OMI-642): which {@code @FullText}
 * field, what to search for, how, and the same narrowing and paging a {@link NearestSpec} carries -- the predicate is
 * applied before the limit, never after.
 *
 * @param field     the {@code @FullText} field, with its language
 * @param text      what to search for: words and phrases, as a person types them ({@code websearch_to_tsquery}'s
 *                  syntax on Postgres: quotes for a phrase, {@code -} to exclude, {@code or})
 * @param fuzzy     by trigram similarity instead of by terms -- for a name, misspelled or damaged by OCR
 * @param limit     maximum hits, after the predicate
 * @param offset    how many of the best matches to skip first
 * @param predicate OR-of-ANDs relational narrowing, in {@link DerivedFinderQuery}'s bound form; empty for none
 */
record TextSpec(
        FullTextFields.Indexed field,
        String text,
        boolean fuzzy,
        int limit,
        int offset,
        List<List<DerivedFinderQuery.BoundPart>> predicate) {

    boolean isNarrowed() {
        return predicate.stream().anyMatch(group -> !group.isEmpty());
    }

    int limitIncludingOffset() {
        return Math.addExact(limit, offset);
    }
}
