package dev.xtrafe.javai.persistence;

import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;

import java.util.List;

/** Geo finders over {@link TestLocatedPlace} (OMI-556). */
interface TestLocatedPlaceRepository extends JavAIRepository<TestLocatedPlace> {

    List<TestLocatedPlace> findByCoordinatesNear(Point center, Distance within);

    List<TestLocatedPlace> findByCoordinatesWithin(Circle circle);
}
