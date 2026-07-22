package dev.xtrafe.javai.persistence;

/** Realized once so the Neo4j backend registers {@link TestReview}'s node label before a
 *  {@code TestVenue -> reviews} relationship traversal needs to resolve it (same reason
 *  {@link TestProfileRepository}/{@link TestTagRepository} exist). */
interface TestReviewRepository extends JavAIRepository<TestReview> {
}
