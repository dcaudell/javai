package dev.xtrafe.javai.e2e.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.data.geo.Point;

import java.util.UUID;

/**
 * A plain, <b>non-{@code @JavAIVectorizable}</b> {@code @Entity} carrying a geo {@code Point} -- exercises,
 * end to end against all three real backends, both the OMI-138 "a non-vectorized entity is a first-class
 * citizen of {@code JavAIRepository}" case and OMI-141's geo {@code Near}/{@code Within} finders. It has no
 * embeddings at all (nothing here touches the Ollama provider); {@code location} round-trips as two columns
 * in Postgres's {@code javai_geo_points} side table, a native Neo4j point, and a MongoDB GeoJSON point
 * respectively. See {@code DerivedFinderE2ETest}.
 */
@Entity
public class Place {

    @Id
    private UUID id;

    private String name;

    private Point location;

    public Place() {
    }

    public Place(String name, Point location) {
        this.name = name;
        this.location = location;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Point getLocation() {
        return location;
    }
}
