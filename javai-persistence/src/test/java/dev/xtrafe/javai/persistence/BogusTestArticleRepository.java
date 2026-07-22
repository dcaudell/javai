package dev.xtrafe.javai.persistence;

import java.util.List;

/**
 * Deliberately unsupported: {@code mostRecent()} is neither the {@code findNearestBy<Field>Vector} vector
 * convention nor an ordinary Spring-Data-style derived finder (no {@code find/count/exists/delete...By}
 * subject), so it must be rejected at repository-creation time. ({@code findByTitle} is now a perfectly valid
 * derived finder on {@link TestArticle} thanks to OMI-138, so it can no longer stand in for "bogus".)
 */
interface BogusTestArticleRepository extends JavAIRepository<TestArticle> {

    List<TestArticle> mostRecent();
}
