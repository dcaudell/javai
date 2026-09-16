package dev.xtrafe.javai.persistence;

import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;

import java.util.List;

/** Geo finders over {@link TestPlace} (OMI-556). */
interface TestPlaceRepository extends JavAIRepository<TestPlace> {

    List<TestPlace> findByCoordinatesNear(Point center, Distance within);

    List<TestPlace> findByCoordinatesWithin(Circle circle);
}
