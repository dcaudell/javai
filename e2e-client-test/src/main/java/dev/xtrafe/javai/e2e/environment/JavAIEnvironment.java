package dev.xtrafe.javai.e2e.environment;

import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.completion.LocalCompletionDefaults;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.AttachmentRepository;
import dev.xtrafe.javai.e2e.domain.CommentRepository;
import dev.xtrafe.javai.e2e.fixtures.SampleDataSeeder;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.vector.LocalEmbeddingDefaults;

/**
 * The single class every e2e test class references to get an already-configured environment -- the
 * embedding provider, both persistence backends' {@code ArticleRepository}, a {@code Cortex}, and seeded
 * mock data, all wired here instead of duplicated across six test classes' own {@code @BeforeAll} methods
 * (as they used to be, each independently calling {@code JavAIRuntime.configureEmbeddingProvider(...)}, and
 * two of them also independently calling {@code JavAIPI.configurePersistence(...)}/{@code
 * JavAIPI.repository(...)} with hardcoded credentials).
 *
 * <p>Initialization happens exactly once per JVM, in the static initializer below -- the same "static field
 * triggers on first reference" idiom this project's predecessor, {@code MonolithicInfrastructure}, already
 * used. A test class's {@code @BeforeAll} should just call {@link #ensureRunning()}: an intentionally
 * no-op method whose only purpose is to force this class to load (and thus run its static initializer) at
 * an explicit, visible point, rather than relying on some other incidental reference to trigger it.
 *
 * <p>{@link JavAIPI#repository(Class)} returns a proxy bound to whichever backend was configured at the
 * moment it was created -- switching {@link JavAIPI#configurePersistence} afterward doesn't retroactively
 * affect an already-created proxy (see that class's own javadoc). That's what lets this class safely build
 * and hand out a Postgres-backed, a Neo4j-backed, <em>and</em> a MongoDB-backed {@code ArticleRepository}
 * here, once, for every test class to share, rather than each test needing to reconstruct its own.
 */
public final class JavAIEnvironment {

    private static final ArticleRepository POSTGRES_ARTICLE_REPOSITORY;
    private static final ArticleRepository NEO4J_ARTICLE_REPOSITORY;
    private static final ArticleRepository MONGO_ARTICLE_REPOSITORY;
    private static final Cortex CORTEX;

    static {
        MonolithicContainer.ensureRunning();
        JavAIRuntime.configureEmbeddingProvider(LocalEmbeddingDefaults.create(MonolithicContainer.embeddingEndpoint()));

        // No CommentRepository/AttachmentRepository pre-registration here: RepositoryBackendHibernatePostgres
        // auto-registers both, recursively, as soon as ArticleRepository is realized -- reachable through
        // Article's own featuredComment/draftComment/attachment/comments/relatedComments fields.
        JavAIPI.configurePersistence(JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(MonolithicContainer.postgresUrl())
                .postgresUsername(MonolithicContainer.POSTGRES_USERNAME)
                .postgresPassword(MonolithicContainer.POSTGRES_PASSWORD)
                .build());
        POSTGRES_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class);

        // Neo4j still needs this today -- only the Postgres backend's ceremony has been automated so far.
        JavAIPI.configurePersistence(JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(MonolithicContainer.neo4jUri())
                .neo4jUsername(MonolithicContainer.NEO4J_USERNAME)
                .neo4jPassword(MonolithicContainer.NEO4J_PASSWORD)
                .build());
        JavAIPI.repository(CommentRepository.class);
        JavAIPI.repository(AttachmentRepository.class);
        NEO4J_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class);

        // No CommentRepository/AttachmentRepository pre-registration here either: RepositoryBackendSpringDataMongo
        // recursively auto-registers related types too, matching Postgres's convenience rather than Neo4j's
        // explicit-registration requirement.
        JavAIPI.configurePersistence(JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri(MonolithicContainer.mongoUri())
                .mongoDatabase("javai")
                .build());
        MONGO_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class);

        CORTEX = LocalCompletionDefaults.create(MonolithicContainer.completionEndpoint());

        SampleDataSeeder.resetAndSeed(MonolithicContainer.postgresUrl(), MonolithicContainer.neo4jUri(), MonolithicContainer.mongoUri(),
                POSTGRES_ARTICLE_REPOSITORY, NEO4J_ARTICLE_REPOSITORY, MONGO_ARTICLE_REPOSITORY);
    }

    private JavAIEnvironment() {
    }

    /** No-op body -- referencing this class (e.g. calling this method from a test's {@code @BeforeAll}) is
     *  what triggers the one-time static initialization above. */
    public static void ensureRunning() {
    }

    public static ArticleRepository postgresArticleRepository() {
        return POSTGRES_ARTICLE_REPOSITORY;
    }

    public static ArticleRepository neo4jArticleRepository() {
        return NEO4J_ARTICLE_REPOSITORY;
    }

    public static ArticleRepository mongoArticleRepository() {
        return MONGO_ARTICLE_REPOSITORY;
    }

    public static Cortex cortex() {
        return CORTEX;
    }
}
