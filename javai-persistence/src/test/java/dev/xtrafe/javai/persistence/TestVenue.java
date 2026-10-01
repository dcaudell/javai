package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.data.geo.Point;

import java.util.List;
import java.util.UUID;

/**
 * The OMI-141 fixture: a plain (non-vectorized) {@code @Entity} carrying a to-many collection of entities
 * ({@code reviews}) and a geo {@code Point} ({@code location}), so one fixture exercises nested-to-many
 * finders, collection-emptiness finders, regex, and geo Near/Within across all three backends. The
 * {@code reviews} collection is a {@link dev.xtrafe.javai.model.JavAIList} initialized to a
 * {@link JavAIArrayList} -- the only supported JavAI collection shape as of OMI-277, mapped natively by
 * Hibernate;
 * the {@code location} {@code Point} rounds-trips through {@code javai_geo_points}/a Neo4j point/a GeoJSON
 * field respectively.
 */
@Entity
final class TestVenue {

    @Id
    private UUID id;

    private String name;

    private Point location;

    @OneToMany(cascade = CascadeType.ALL)
    private JavAIList<TestReview> reviews = new JavAIArrayList<>();

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

    JavAIList<TestReview> getReviews() {
        return reviews;
    }
}
