package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIArrayList;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.data.geo.Point;

import java.util.List;
import java.util.UUID;

/**
 * The OMI-141 fixture: a plain (non-vectorized) {@code @Entity} carrying a to-many collection of entities
 * ({@code reviews}) and a geo {@code Point} ({@code location}), so one fixture exercises nested-to-many
 * finders, collection-emptiness finders, regex, and geo Near/Within across all three backends. The
 * {@code reviews} collection is a {@link JavAIArrayList} so the Postgres backend maps it out-of-band through
 * {@code javai_collection_members} (the same reason {@code TestArticleWithTags} uses JavAI collection types);
 * the {@code location} {@code Point} rounds-trips through {@code javai_geo_points}/a Neo4j point/a GeoJSON
 * field respectively.
 */
@Entity
final class TestVenue {

    @Id
    private UUID id;

    private String name;

    private Point location;

    private final JavAIArrayList<TestReview> reviews = new JavAIArrayList<>();

    TestVenue() {
    }

    TestVenue(String name, Point location, List<TestReview> reviews) {
        this.name = name;
        this.location = location;
        this.reviews.addAll(reviews);
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    Point getLocation() {
        return location;
    }

    JavAIArrayList<TestReview> getReviews() {
        return reviews;
    }
}
