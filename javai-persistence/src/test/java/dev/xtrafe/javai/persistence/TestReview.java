package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * A plain related entity held in {@link TestVenue}'s to-many {@code reviews} collection -- the element type
 * OMI-141's nested-to-many and collection-emptiness derived finders reach through
 * ({@code findByReviewsReviewer}, {@code findByReviewsIsEmpty}). Non-vectorized, like {@link TestAccount}.
 */
@Entity
final class TestReview {

    @Id
    private UUID id;

    private String reviewer;

    private int rating;

    TestReview() {
    }

    TestReview(String reviewer, int rating) {
        this.reviewer = reviewer;
        this.rating = rating;
    }

    UUID getId() {
        return id;
    }

    String getReviewer() {
        return reviewer;
    }

    int getRating() {
        return rating;
    }
}
