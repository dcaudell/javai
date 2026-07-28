package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.scanned.ScannedOnlyEntity;
import dev.xtrafe.javai.persistence.scanned.ScannedOnlyEntityRepository;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-214: when the registration window is closed, and what happens to a repository realized after it.
 *
 * <h2>The problem this pins</h2>
 *
 * The Postgres backend accumulates entity classes across {@code JavAIPI.repository(...)} calls and builds
 * its single {@code SessionFactory} lazily, on first actual use. Hibernate's metadata is immutable once
 * built, so the documented contract is "register everything before invoking anything."
 *
 * <p>The rule is fine. What is not fine is that <b>the moment it starts applying is invisible</b> -- an
 * ordinary-looking method call somewhere else in the application closes the window -- so correctness became
 * a property of global startup ordering that nothing local can check. Downstream, {@code omiai-platform}
 * pays for that with a hand-maintained {@code @DependsOn} list of 18 bean names that had already drifted out
 * of sync with its own bean declarations by two entries.
 *
 * <p>These tests fix the semantics at the two things that actually matter to a consumer: a late
 * registration that introduces <em>nothing new</em> must be harmless, and one that genuinely introduces an
 * unknown type must fail with a message naming <em>what closed the window</em> rather than only what
 * arrived after it.
 */
@Testcontainers
class RegistrationLifecycleTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void configureProvider() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
    }

    /** A fresh config each time, so each test gets its own backend and its own unbuilt factory. */
    private static JavAIPersistenceConfig.Builder configBuilder() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .hibernateProperty("javai.test.scope", "registration-" + System.nanoTime());
    }

    /**
     * The headline fix. Re-realizing a repository whose type is already registered must be a no-op, not an
     * error -- it introduces nothing Hibernate would need to know about, so there is nothing for the frozen
     * metadata to be missing.
     *
     * <p>Without this, declaring entity types up front cannot solve the ordering problem: every late
     * {@code repository()} call would still fail, however completely the types had been declared.
     */
    @Test
    void realizingAnAlreadyRegisteredRepositoryAfterTheFactoryIsBuiltIsHarmless() {
        JavAIPersistenceConfig config = configBuilder().build();
        TestArticleRepository articles = JavAIPI.repository(TestArticleRepository.class, config);

        articles.findAll(); // closes the registration window

        assertDoesNotThrow(() -> JavAIPI.repository(TestArticleRepository.class, config),
                "the type is already registered, so re-realizing its repository asks nothing new of Hibernate");
    }

    /**
     * The same, for a type registered only because something else pulled it in. {@code TestImageCover} is
     * reachable from {@code TestAlbum} solely through an {@code @Any} discriminator (OMI-212), so it is
     * registered without ever having had a repository of its own -- and asking for one later must still be
     * harmless.
     */
    @Test
    void realizingARepositoryForATypeRegisteredByDiscoveryIsHarmless() {
        JavAIPersistenceConfig config = configBuilder().build();
        TestAlbumRepository albums = JavAIPI.repository(TestAlbumRepository.class, config);

        albums.findAll();

        assertDoesNotThrow(() -> JavAIPI.repository(TestImageCoverRepository.class, config),
                "a type discovered through another entity is registered just as fully as one asked for");
    }

    /** Declaring types on the config removes the ordering constraint entirely for those types. */
    @Test
    void typesDeclaredOnTheConfigCanHaveRepositoriesRealizedAtAnyTime() {
        JavAIPersistenceConfig config = configBuilder()
                .entityType(TestOrphanEntity.class)
                .build();
        TestArticleRepository articles = JavAIPI.repository(TestArticleRepository.class, config);

        articles.findAll();

        TestOrphanEntityRepository orphans =
                assertDoesNotThrow(() -> JavAIPI.repository(TestOrphanEntityRepository.class, config),
                        "a type named on the config is registered before anything can be built");
        assertNotNull(orphans.findAll());
    }

    /**
     * The point of the whole exercise: with the packages scanned, entity registration is complete before
     * anything can be built, so a repository may be realized whenever it is convenient -- including after
     * the factory exists. Nothing about startup ordering has to be arranged, declared, or maintained.
     *
     * <p>{@code ScannedOnlyEntity} is referenced by no field on any other entity and named by no other
     * fixture, so scanning is the only thing that could have registered it.
     */
    @Test
    void scanningAPackageRemovesTheOrderingConstraintEntirely() {
        JavAIPersistenceConfig config = configBuilder()
                .entityPackages("dev.xtrafe.javai.persistence.scanned")
                .build();
        TestArticleRepository articles = JavAIPI.repository(TestArticleRepository.class, config);

        articles.findAll(); // would have closed the window

        ScannedOnlyEntityRepository scanned =
                assertDoesNotThrow(() -> JavAIPI.repository(ScannedOnlyEntityRepository.class, config),
                        "a scanned package makes the entity set a property of the config, not of call order");
        assertNotNull(scanned.findAll());
    }

    /**
     * Scanning validates what it finds, exactly as a named type is validated -- registering an entity JavAI
     * cannot map would be worse than refusing it, since Hibernate maps it regardless and JavAI's half would
     * be wrong. But the caller never named this type, so the error has to say where it came from and how to
     * stop pulling it in.
     */
    @Test
    void scanningAPackageContainingAnUnmappableEntityExplainsWhereItCameFrom() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(TestArticleRepository.class, configBuilder()
                        .entityPackages("dev.xtrafe.javai.persistence")
                        .build()));

        assertTrue(thrown.getMessage().contains("Scanning"),
                "the message must say the type arrived by scanning: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("non-vectorized"),
                "and must rule out the wrong conclusion -- a plain @Entity is never refused for lacking "
                        + "vectors: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("excludeEntityType")
                        && thrown.getMessage().contains("@PersistenceIgnore"),
                "and must offer the ways out for a type that belongs to another persistence unit: "
                        + thrown.getMessage());
    }

    /** Scanning finds nothing under a package that has no entities, and that is not an error by itself. */
    @Test
    void scanningAPackageWithNoEntitiesIsHarmless() {
        JavAIPersistenceConfig config = configBuilder()
                .entityPackages("dev.xtrafe.javai.persistence.nosuchpackage")
                .build();

        assertDoesNotThrow(() -> JavAIPI.repository(TestArticleRepository.class, config),
                "an empty scan must not stop an application from starting");
    }

    /**
     * The residual case that genuinely cannot work: a type nothing knew about, arriving after the metadata
     * is frozen. It must still fail -- but the message has to name <b>what built the factory</b>, because
     * that is the thing the consumer has to move, and it is never the class named in today's error.
     */
    @Test
    void agenuinelyUnknownTypeArrivingLateFailsAndNamesWhatClosedTheWindow() {
        JavAIPersistenceConfig config = configBuilder().build();
        TestArticleRepository articles = JavAIPI.repository(TestArticleRepository.class, config);

        articles.findAll();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> JavAIPI.repository(TestOrphanEntityRepository.class, config));

        assertTrue(thrown.getMessage().contains(TestOrphanEntity.class.getName()),
                "the message must name the type that could not be registered: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("RegistrationLifecycleTest"),
                "and must name the call that built the factory, which is the thing to move: "
                        + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("entityType"),
                "and should point at the way out (declaring types on the config): " + thrown.getMessage());
    }
}
