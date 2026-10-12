package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.Ranked;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** @see TestPassage. The method-name full-text forms are declared here so creation-time validation is under test. */
interface TestPassageRepository extends JavAIRepository<TestPassage> {

    List<TestPassage> findMatchingByText(String query, int limit);

    List<Ranked<TestPassage>> findMatchingByTextAndRulesetIs(String query, int limit, String ruleset);

    List<TestPassage> findMatchingByText(String query, Pageable page);

    List<TestPassage> findSimilarByName(String name, Limit limit);
}
