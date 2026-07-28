package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-212: Hibernate's {@code @Any} mapping, whose targets are named only by
 * {@code @AnyDiscriminatorValue(entity = ...)} and are reachable through no declared field type.
 *
 * <p>The failure this pins was <b>at {@code SessionFactory} boot</b>, not at the point of touching an
 * {@code @Any} field -- so it took out every repository call in the configuration, including ones that had
 * nothing to do with the association. Registering a repository for {@link TestAlbum} alone used to throw
 * {@code UnknownEntityTypeException} for {@link TestLottieCover}.
 *
 * <p>The whole test rests on {@link TestImageCover}/{@link TestLottieCover} being reachable <em>only</em>
 * through the discriminator. Referencing either from any other fixture -- even a field nobody reads --
 * would register it by the ordinary field walk and make this pass for the wrong reason.
 */
@Testcontainers
class AnyAssociationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TestAlbumRepository albums;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        // Deliberately the only repository requested: no TestImageCoverRepository, no TestLottieCoverRepository.
        // Requesting those too is the workaround the ticket describes, and would hide the defect.
        albums = JavAIPI.repository(TestAlbumRepository.class, config);
    }

    @Test
    void aRepositoryWhoseOnlyReferenceToATypeIsAnAnyDiscriminatorBoots() {
        assertNotNull(albums, "building the SessionFactory must not fail for want of an @Any target type");
        assertNotNull(albums.findAll(), "and every ordinary operation must work, not just @Any ones");
    }

    @Test
    void anAnyAssociationRoundTripsToItsFirstConcreteType() {
        TestAlbum album = albums.save(new TestAlbum("album with an image cover", new TestImageCover("cover art")));

        TestAlbum reloaded = albums.findById(album.getId()).orElseThrow();

        TestCover cover = reloaded.getCover();
        assertInstanceOf(TestImageCover.class, cover,
                "the discriminator must resolve to the concrete type that was stored");
        assertEquals("cover art", cover.label());
    }

    @Test
    void anAnyAssociationRoundTripsToItsSecondConcreteType() {
        TestAlbum album = albums.save(new TestAlbum("album with a lottie cover", new TestLottieCover("animated")));

        TestAlbum reloaded = albums.findById(album.getId()).orElseThrow();

        assertInstanceOf(TestLottieCover.class, reloaded.getCover(),
                "a second, unrelated target type must resolve just as well as the first -- polymorphism is "
                        + "the entire point of @Any");
        assertEquals("animated", reloaded.getCover().label());
    }

    @Test
    void twoAlbumsMayPointAtDifferentConcreteTypesSimultaneously() {
        UUID imageBacked = albums.save(new TestAlbum("image-backed", new TestImageCover("photo"))).getId();
        UUID lottieBacked = albums.save(new TestAlbum("lottie-backed", new TestLottieCover("motion"))).getId();

        assertInstanceOf(TestImageCover.class, albums.findById(imageBacked).orElseThrow().getCover());
        assertInstanceOf(TestLottieCover.class, albums.findById(lottieBacked).orElseThrow().getCover());
    }

    /**
     * The other two backends reject {@code @Any} at registration rather than dropping it silently.
     *
     * <p>Measured before this was added: Neo4j saved a {@code TestAlbum} happily and returned {@code cover ==
     * null} on reload. Their mapping is hand-rolled with no discriminator concept, and reference detection
     * keys off the declared field type -- a plain interface matches neither the reference nor the
     * simple-value path, so the field fell into the documented "silently skipped" boundary. Losing data
     * quietly is worse than refusing the mapping, so both now fail loudly and name the backend that does
     * support it, exactly as {@code KnowledgeGraph} fields already do in reverse.
     *
     * <p>No container needed: registration validates by reflection, long before any connection is opened.
     */
    @Test
    void neo4jAndMongoRejectAnyFieldsAtRegistrationRatherThanSilentlyDroppingThem() {
        for (JavAIPersistenceConfig.Backend backend : List.of(
                JavAIPersistenceConfig.Backend.NEO4J, JavAIPersistenceConfig.Backend.MONGODB)) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> JavAIPI.repository(TestAlbumRepository.class, JavAIPersistenceConfig.builder()
                            .backend(backend)
                            .neo4jUri("bolt://localhost:7687").neo4jUsername("neo4j").neo4jPassword("unused")
                            .mongoUri("mongodb://localhost:27017").mongoDatabase("unused")
                            .build()),
                    backend + " must refuse an @Any field instead of dropping it");

            assertTrue(thrown.getMessage().contains("@Any"), "the error must name the feature: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains("POSTGRES"),
                    "and point at the backend that does support it: " + thrown.getMessage());
        }
    }

    @Test
    void anAnyAssociationMayBeNull() {
        TestAlbum album = albums.save(new TestAlbum("no cover at all", null));

        assertNotNull(albums.findById(album.getId()).orElseThrow(),
                "an unset polymorphic reference is ordinary, not an error");
    }
}
