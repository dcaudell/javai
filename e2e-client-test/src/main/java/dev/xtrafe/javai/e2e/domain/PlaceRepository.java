package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.persistence.JavAIRepository;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;

import java.util.List;

/**
 * Realized via {@code JavAIPI.repository(PlaceRepository.class, config)} for each backend (see
 * {@code JavAIEnvironment}). Exercises geo {@code Near}/{@code Within} and a couple of plain relational
 * finders over a non-vectorized entity, end to end. See {@code DerivedFinderE2ETest}.
 */
public interface PlaceRepository extends JavAIRepository<Place> {

    List<Place> findByLocationNear(Point center, Distance within);

    List<Place> findByLocationWithin(Circle circle);

    List<Place> findByName(String name);

    List<Place> findByNameRegex(String pattern);
}
