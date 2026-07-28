package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleCluster;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.domain.CommentRepository;
import dev.xtrafe.javai.e2e.domain.Place;
import dev.xtrafe.javai.e2e.domain.PlaceRepository;
import dev.xtrafe.javai.e2e.environment.JavAIEnvironment;
import dev.xtrafe.javai.e2e.environment.MonolithicContainer;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-214, end to end: repositories realized in every order that used to matter, against the real Postgres
 * and the real load-time weaver.
 *
 * <h2>What used to happen</h2>
 *
 * The Postgres backend accumulates entity classes as repositories are realized and builds one Hibernate
 * {@code SessionFactory} lazily, at the first actual repository call. Hibernate's metadata is immutable once
 * built, so registration had to finish before <em>anything</em> was used -- and the moment that window shut
 * was invisible, caused by an ordinary method call somewhere else in the application.
 *
 * <p>Consumers paid for it in startup choreography they had to maintain by hand: {@code omiai-platform}
 * carried an 18-name {@code @DependsOn} list on its factory bean, which had already drifted two entries out
 * of sync with its own bean declarations, one of them registering from <em>inside</em> a library call where
 * no amount of care reading the configuration would have revealed it.
 *
 * <p>Each test below names whether it would have failed before this change, so the file doubles as the
 * before/after record. Every one of them runs against the same real database an application would use, not
 * against a mock of the registration logic.
 *
 * <h2>The one real exclusion this file needs, and why it is the config form</h2>
 *
 * Scanning {@code dev.xtrafe.javai.e2e.domain} finds {@link ArticleCluster}, which declares a
 * {@code KnowledgeGraph} field. That is Neo4j-only, so a Postgres configuration refuses it -- correctly, and
 * for one of exactly three reasons scanning ever refuses anything, none of which is "not vectorized".
 *
 * <p>It is excluded here with {@code excludeEntityType(...)} rather than by annotating the class
 * {@code @PersistenceIgnore}, and the difference matters: {@code ArticleCluster} is genuinely JavAI's to
 * persist, just on a different backend, and {@code KnowledgeGraphPersistenceE2ETest} depends on that. The
 * annotation is global and would exclude it from the Neo4j configuration too. The config form says "not
 * <em>this</em> configuration's", which is the accurate statement.
 *
 * <h2>Why each test builds its own config</h2>
 *
 * {@code JavAIPI} caches one backend per {@code JavAIPersistenceConfig}, so a shared config would mean a
 * shared, already-built {@code SessionFactory} and there would be no ordering left to exercise. Each test
 * therefore takes a distinct config -- distinguished by a throwaway Hibernate property -- giving it its own
 * backend with its own unbuilt factory, which is exactly the state a freshly-started application is in.
 */
class RepositoryRegistrationOrderingE2ETest {

    private static final AtomicLong UNIQUE = new AtomicLong();

    @BeforeAll
    static void ensureEnvironment() {
        JavAIEnvironment.ensureRunning();
    }

    /** A config nothing else shares, so this test's backend has its own unbuilt SessionFactory. */
    private static JavAIPersistenceConfig.Builder freshConfig() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(MonolithicContainer.postgresUrl())
                .postgresUsername(MonolithicContainer.POSTGRES_USERNAME)
                .postgresPassword(MonolithicContainer.POSTGRES_PASSWORD)
                .hibernateProperty("javai.test.ordering", "run-" + UNIQUE.incrementAndGet());
    }

    // ---- orderings that always worked -------------------------------------------------------------

    /** The documented discipline: realize everything, then use anything. The control. */
    @Test
    void registerEverythingThenUseAnything() {
        JavAIPersistenceConfig config = freshConfig().build();
        ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
        PlaceRepository places = JavAIPI.repository(PlaceRepository.class, config);

        assertNotNull(articles.findAll());
        assertNotNull(places.findAll());
    }

    // ---- orderings that used to fail and now pass --------------------------------------------------

    /**
     * <b>Previously failed.</b> Re-realizing a repository already in use threw, because registration
     * refused any call once the factory existed -- without checking whether the type was already known. The
     * commonest real-world shape of this is a Spring bean method invoked more than once, or two modules each
     * asking for the repository they need.
     */
    @Test
    void reRealizingARepositoryAlreadyInUse() {
        JavAIPersistenceConfig config = freshConfig().build();
        ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
        articles.findAll(); // closes the registration window

        ArticleRepository again = assertDoesNotThrow(() -> JavAIPI.repository(ArticleRepository.class, config));
        assertNotNull(again.findAll());
    }

    /**
     * <b>Previously failed.</b> {@code Comment} is reachable from {@code Article} through several
     * associations, so realizing {@code ArticleRepository} already registered it. Asking for its own
     * repository afterwards introduces nothing new -- but used to throw anyway.
     *
     * <p>This is the shape a lazily-initialized bean produces: the type is in the metadata, the application
     * simply had no reason to ask for its repository until later.
     */
    @Test
    void realizingARepositoryForATypeAlreadyPulledInByAnother() {
        JavAIPersistenceConfig config = freshConfig().build();
        ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
        articles.findAll();

        CommentRepository comments =
                assertDoesNotThrow(() -> JavAIPI.repository(CommentRepository.class, config));
        assertNotNull(comments.findAll());
    }

    /**
     * <b>Previously failed.</b> {@code Place} shares no association with {@code Article}, so nothing would
     * have registered it -- naming it on the config is what makes the ordering irrelevant.
     */
    @Test
    void realizingAnUnrelatedRepositoryLateWhenItsTypeWasNamedOnTheConfig() {
        JavAIPersistenceConfig config = freshConfig().entityType(Place.class).build();
        ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
        articles.findAll();

        PlaceRepository places = assertDoesNotThrow(() -> JavAIPI.repository(PlaceRepository.class, config));
        assertNotNull(places.findAll());
    }

    /**
     * <b>Previously failed.</b> The same, with the whole domain package scanned rather than one type named --
     * the form that scales, and the one that lets a Spring application delete its {@code @DependsOn} list.
     */
    @Test
    void realizingAnyRepositoryLateWhenTheDomainPackageWasScanned() {
        JavAIPersistenceConfig config = freshConfig()
                .entityPackages("dev.xtrafe.javai.e2e.domain")
                .excludeEntityType(ArticleCluster.class)
                .build();
        ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
        articles.findAll();

        PlaceRepository places = assertDoesNotThrow(() -> JavAIPI.repository(PlaceRepository.class, config));
        assertNotNull(places.findAll());
    }

    /**
     * <b>Previously failed.</b> The second, less obvious freeze point: asking for the {@code SessionFactory}
     * builds it. Every application wiring Spring's transaction manager onto JavAI's factory (the documented
     * OMI-160 pattern) triggers this, usually before its repository beans have all been created.
     */
    @Test
    void askingForTheSessionFactoryFirstThenRealizingRepositories() {
        JavAIPersistenceConfig config = freshConfig()
                .entityPackages("dev.xtrafe.javai.e2e.domain")
                .excludeEntityType(ArticleCluster.class)
                .build();

        assertNotNull(JavAIPI.sessionFactory(config), "asking for the factory builds it");

        ArticleRepository articles = assertDoesNotThrow(() -> JavAIPI.repository(ArticleRepository.class, config));
        PlaceRepository places = assertDoesNotThrow(() -> JavAIPI.repository(PlaceRepository.class, config));
        assertNotNull(articles.findAll());
        assertNotNull(places.findAll());
    }

    /**
     * <b>Previously failed.</b> Fully interleaved -- realize, use, realize, use -- which is what an
     * application with lazily-created beans actually does, and the pattern no {@code @DependsOn} list can
     * express because the calls are not all at startup.
     */
    @Test
    void fullyInterleavedRealizeAndUse() {
        JavAIPersistenceConfig config = freshConfig()
                .entityPackages("dev.xtrafe.javai.e2e.domain")
                .excludeEntityType(ArticleCluster.class)
                .build();

        ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
        assertNotNull(articles.findAll());

        CommentRepository comments = JavAIPI.repository(CommentRepository.class, config);
        assertNotNull(comments.findAll());

        PlaceRepository places = JavAIPI.repository(PlaceRepository.class, config);
        assertNotNull(places.findAll());

        // ...and back to the first, after two more windows would have closed.
        assertNotNull(JavAIPI.repository(ArticleRepository.class, config).findAll());
    }

    /**
     * <b>Previously failed.</b> A scanned configuration still persists and reads real data -- registration
     * by scanning is not a weaker registration than registration by repository.
     */
    @Test
    void aScannedConfigurationRoundTripsRealData() {
        JavAIPersistenceConfig config = freshConfig()
                .entityPackages("dev.xtrafe.javai.e2e.domain")
                .excludeEntityType(ArticleCluster.class)
                .build();
        JavAIPI.sessionFactory(config); // build first, so everything below is "late"

        ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
        Article saved = articles.save(new Article("ordering probe " + UNIQUE.incrementAndGet(),
                "written through a configuration whose entities were discovered by scanning"));

        Article reloaded = articles.findById(saved.getId()).orElseThrow();
        assertNotNull(reloaded.getBody());

        Comment comment = JavAIPI.repository(CommentRepository.class, config)
                .save(new Comment("ordering-probe", "a comment saved through a late-realized repository"));
        assertNotNull(comment.getId());
    }

    // ---- the one ordering that still cannot work ---------------------------------------------------

    /**
     * A type nothing knew about, arriving after the metadata is frozen, with nothing declared on the config.
     * This cannot be made to work -- Hibernate's metadata really is immutable -- so it still fails.
     *
     * <p>What changed is the message. It used to name only the repository that arrived late, which is never
     * the thing to move; it now names the call that <em>built the factory</em>, and points at the two ways
     * to stop needing the ordering at all.
     */
    @Test
    void agenuinelyUnknownTypeArrivingLateStillFailsButExplainsItself() {
        JavAIPersistenceConfig config = freshConfig().build();
        ArticleRepository articles = JavAIPI.repository(ArticleRepository.class, config);
        articles.findAll();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> JavAIPI.repository(PlaceRepository.class, config));

        assertTrue(thrown.getMessage().contains(Place.class.getName()),
                "names the type that could not be registered: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("RepositoryRegistrationOrderingE2ETest"),
                "names the call that closed the window, which is the thing to move: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("entityType")
                        && thrown.getMessage().contains("entityPackages"),
                "and points at the ways out: " + thrown.getMessage());
    }
}
