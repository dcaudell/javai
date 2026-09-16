package dev.xtrafe.javai.persistence;

import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Point;

import java.util.List;

/** Geo finders over {@link TestFlatPlace} (OMI-556). */
interface TestFlatPlaceRepository extends JavAIRepository<TestFlatPlace> {

    List<TestFlatPlace> findByCoordinatesNear(Point center, Distance within);

    List<TestFlatPlace> findByCoordinatesWithin(Circle circle);
}
