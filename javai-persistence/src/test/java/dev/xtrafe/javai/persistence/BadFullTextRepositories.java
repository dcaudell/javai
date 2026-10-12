package dev.xtrafe.javai.persistence;

import java.util.List;

/** Full-text method-name shapes that must be refused when the repository is created (OMI-642). */
final class BadFullTextRepositories {

    private BadFullTextRepositories() {
    }

    /** {@code text} is not declared fuzzy. */
    interface FuzzyOnPlainField extends JavAIRepository<TestPassage> {
        List<TestPassage> findSimilarByText(String query, int limit);
    }

    /** {@code ruleset} is not {@code @FullText}. */
    interface NotAFullTextField extends JavAIRepository<TestPassage> {
        List<TestPassage> findMatchingByRuleset(String query, int limit);
    }

    /** No bound: no limit, Pageable or Limit. */
    interface Unbounded extends JavAIRepository<TestPassage> {
        List<TestPassage> findMatchingByText(String query);
    }
}
