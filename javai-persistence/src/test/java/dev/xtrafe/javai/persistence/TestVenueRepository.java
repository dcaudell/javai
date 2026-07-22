package dev.xtrafe.javai.persistence;

import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;

import java.util.List;

/**
 * OMI-141 derived finders exercised across all three backends: nested traversal through a to-many
 * collection ({@code findByReviews...}), collection emptiness ({@code IsEmpty}/{@code IsNotEmpty}), the
 * {@code Regex}/{@code Matches} operator, and geo {@code Near}/{@code Within} over a {@code Point} field.
 */
interface TestVenueRepository extends JavAIRepository<TestVenue> {

    List<TestVenue> findByReviewsReviewer(String reviewer);

    List<TestVenue> findByReviewsRatingGreaterThanEqual(int rating);

    List<TestVenue> findByReviewsIsEmpty();

    List<TestVenue> findByReviewsIsNotEmpty();

    long countByReviewsIsEmpty();

    List<TestVenue> findByNameRegex(String pattern);

    List<TestVenue> findByNameMatches(String pattern);

    List<TestVenue> findByLocationNear(Point center, Distance within);

    List<TestVenue> findByLocationWithin(Circle circle);
}
