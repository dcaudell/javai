package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OMI-556: a {@code Point} on an entity whose hierarchy includes a {@code @MappedSuperclass} must not break
 * {@code SessionFactory} boot, whether the {@code Point} is declared on the entity or inherited from the base.
 * Each variant gets its own config, so its own {@code SessionFactory}: one failing boot cannot mask another.
 */
@Testcontainers
class GeoPointMappedSuperclassTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final Point PORTLAND = new Point(-122.6765, 45.5231);
    private static final Point DENVER = new Point(-104.9903, 39.7392);

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    private static JavAIPersistenceConfig freshConfig() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
    }

    @Test
    void aPointDeclaredOnASubclassOfAMappedSuperclassBootsAndQueries() {
        TestPlaceRepository places = JavAIPI.repository(TestPlaceRepository.class, freshConfig());
        places.save(new TestPlace("near", new TestPlaceDetails("near details"), PORTLAND));
        places.save(new TestPlace("far", new TestPlaceDetails("far details"), DENVER));

        List<TestPlace> near = places.findByCoordinatesNear(PORTLAND, new Distance(50, Metrics.KILOMETERS));
        assertEquals(List.of("near"), near.stream().map(TestPlace::getName).toList());
        assertEquals("near details", near.get(0).getDetails().getNote(), "details must load as the @OneToOne");
        assertEquals(PORTLAND, near.get(0).getCoordinates());
        assertEquals(List.of("near"), places.findByCoordinatesWithin(new Circle(PORTLAND,
                new Distance(50, Metrics.KILOMETERS))).stream().map(TestPlace::getName).toList());
    }

    @Test
    void aPointInheritedFromAMappedSuperclassBootsAndQueries() {
        TestLocatedPlaceRepository places = JavAIPI.repository(TestLocatedPlaceRepository.class, freshConfig());
        places.save(new TestLocatedPlace("near", new TestPlaceDetails("near details"), PORTLAND));
        places.save(new TestLocatedPlace("far", new TestPlaceDetails("far details"), DENVER));

        List<TestLocatedPlace> near = places.findByCoordinatesNear(PORTLAND, new Distance(50, Metrics.KILOMETERS));
        assertEquals(List.of("near"), near.stream().map(TestLocatedPlace::getName).toList());
        assertEquals("near details", near.get(0).getDetails().getNote(), "details must load as the @OneToOne");
        assertEquals(PORTLAND, near.get(0).getCoordinates());
        assertEquals(List.of("near"), places.findByCoordinatesWithin(new Circle(PORTLAND,
                new Distance(50, Metrics.KILOMETERS))).stream().map(TestLocatedPlace::getName).toList());
    }

    @Test
    void theFlatControlBootsAndQueries() {
        TestFlatPlaceRepository places = JavAIPI.repository(TestFlatPlaceRepository.class, freshConfig());
        places.save(new TestFlatPlace("near", new TestPlaceDetails("near details"), PORTLAND));
        places.save(new TestFlatPlace("far", new TestPlaceDetails("far details"), DENVER));

        List<TestFlatPlace> near = places.findByCoordinatesNear(PORTLAND, new Distance(50, Metrics.KILOMETERS));
        assertEquals(List.of("near"), near.stream().map(TestFlatPlace::getName).toList());
        assertEquals("near details", near.get(0).getDetails().getNote(), "details must load as the @OneToOne");
        assertEquals(PORTLAND, near.get(0).getCoordinates());
        assertEquals(List.of("near"), places.findByCoordinatesWithin(new Circle(PORTLAND,
                new Distance(50, Metrics.KILOMETERS))).stream().map(TestFlatPlace::getName).toList());
    }
}
