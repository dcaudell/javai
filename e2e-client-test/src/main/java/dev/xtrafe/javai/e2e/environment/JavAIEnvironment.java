package dev.xtrafe.javai.e2e.environment;

import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.completion.LocalCompletionDefaults;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.AttachmentRepository;
import dev.xtrafe.javai.e2e.domain.CommentRepository;
import dev.xtrafe.javai.e2e.fixtures.SampleDataSeeder;
import dev.xtrafe.javai.e2e.tagging.TagRepository;
import dev.xtrafe.javai.e2e.tagging.TagSetRepository;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.tagging.JavAITagging;
import dev.xtrafe.javai.vector.LocalEmbeddingDefaults;

/**
 * The single class every e2e test class references to get an already-configured environment -- the
 * embedding provider, all three persistence backends' {@code ArticleRepository}/{@code CommentRepository}/
 * {@code TagRepository}/{@code TagSetRepository}, a {@code Cortex}, and seeded mock data, all wired here
 * instead of duplicated across test classes' own {@code @BeforeAll} methods (as they used to be, each
 * independently calling {@code JavAIRuntime.configureEmbeddingProvider(...)}, and two of them also
 * independently calling {@code JavAIPI.configurePersistence(...)}/{@code JavAIPI.repository(...)} with
 * hardcoded credentials).
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
 *
 * <p><b>{@code JavAITagging} does not share that per-call-proxy binding.</b> Its own static facade
 * (addTag/removeTag/tagsOf/classify/tagSimilarityIndex/...) always resolves against whichever
 * {@code JavAIPersistenceConfig} was passed to the <em>most recent</em> {@code JavAITagging.configureTagging(...)}
 * call -- a single ambient "current backend" pointer, the same posture {@code JavAIPI} itself has for
 * building new proxies, but with no analogous per-proxy escape hatch. {@link #activatePostgresTagging()}/
 * {@link #activateNeo4jTagging()}/{@link #activateMongoTagging()} make that switch explicit: each tagging
 * test method calls its own backend's variant first, so behavior never depends on test execution order.
 */
public final class JavAIEnvironment {

    private static final ArticleRepository POSTGRES_ARTICLE_REPOSITORY;
    private static final ArticleRepository NEO4J_ARTICLE_REPOSITORY;
    private static final ArticleRepository MONGO_ARTICLE_REPOSITORY;

    private static final CommentRepository POSTGRES_COMMENT_REPOSITORY;
    private static final CommentRepository NEO4J_COMMENT_REPOSITORY;
    private static final CommentRepository MONGO_COMMENT_REPOSITORY;

    private static final TagRepository POSTGRES_TAG_REPOSITORY;
    private static final TagRepository NEO4J_TAG_REPOSITORY;
    private static final TagRepository MONGO_TAG_REPOSITORY;

    private static final TagSetRepository POSTGRES_TAG_SET_REPOSITORY;
    private static final TagSetRepository NEO4J_TAG_SET_REPOSITORY;
    private static final TagSetRepository MONGO_TAG_SET_REPOSITORY;

    private static final JavAIPersistenceConfig POSTGRES_CONFIG;
    private static final JavAIPersistenceConfig NEO4J_CONFIG;
    private static final JavAIPersistenceConfig MONGO_CONFIG;

    private static final Cortex CORTEX;

    static {
        MonolithicContainer.ensureRunning();
        JavAIRuntime.configureEmbeddingProvider(LocalEmbeddingDefaults.create(MonolithicContainer.embeddingEndpoint()));

        // No CommentRepository/AttachmentRepository pre-registration here: RepositoryBackendHibernatePostgres
        // auto-registers both, recursively, as soon as ArticleRepository is realized -- reachable through
        // Article's own featuredComment/draftComment/attachment/comments/relatedComments fields. Tag/TagSet
        // are unrelated to Article, though, so still need their own explicit repository() call to get a
        // usable proxy, same as CommentRepository always has.
        POSTGRES_CONFIG = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(MonolithicContainer.postgresUrl())
                .postgresUsername(MonolithicContainer.POSTGRES_USERNAME)
                .postgresPassword(MonolithicContainer.POSTGRES_PASSWORD)
                .build();
        JavAIPI.configurePersistence(POSTGRES_CONFIG);
        POSTGRES_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class);
        POSTGRES_COMMENT_REPOSITORY = JavAIPI.repository(CommentRepository.class);
        POSTGRES_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class);
        POSTGRES_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class);

        // Neo4j still needs explicit registration for every independently-queried type -- only the Postgres
        // backend's related-type auto-registration has been automated so far.
        NEO4J_CONFIG = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(MonolithicContainer.neo4jUri())
                .neo4jUsername(MonolithicContainer.NEO4J_USERNAME)
                .neo4jPassword(MonolithicContainer.NEO4J_PASSWORD)
                .build();
        JavAIPI.configurePersistence(NEO4J_CONFIG);
        NEO4J_COMMENT_REPOSITORY = JavAIPI.repository(CommentRepository.class);
        JavAIPI.repository(AttachmentRepository.class);
        NEO4J_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class);
        NEO4J_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class);
        NEO4J_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class);

        // No CommentRepository/AttachmentRepository pre-registration here either: RepositoryBackendSpringDataMongo
        // recursively auto-registers related types too, matching Postgres's convenience rather than Neo4j's
        // explicit-registration requirement.
        MONGO_CONFIG = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri(MonolithicContainer.mongoUri())
                .mongoDatabase("javai")
                .build();
        JavAIPI.configurePersistence(MONGO_CONFIG);
        MONGO_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class);
        MONGO_COMMENT_REPOSITORY = JavAIPI.repository(CommentRepository.class);
        MONGO_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class);
        MONGO_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class);

        CORTEX = LocalCompletionDefaults.create(MonolithicContainer.completionEndpoint());
        // Classification config is not backend-specific -- a separate field entirely from the
        // "current config" pointer configureTagging(...) switches -- so it's configured once, here, for
        // every backend's own classify()/classifyAll() calls to share.
        JavAITagging.configureClassification(CORTEX);

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

    public static CommentRepository postgresCommentRepository() {
        return POSTGRES_COMMENT_REPOSITORY;
    }

    public static CommentRepository neo4jCommentRepository() {
        return NEO4J_COMMENT_REPOSITORY;
    }

    public static CommentRepository mongoCommentRepository() {
        return MONGO_COMMENT_REPOSITORY;
    }

    public static TagRepository postgresTagRepository() {
        return POSTGRES_TAG_REPOSITORY;
    }

    public static TagRepository neo4jTagRepository() {
        return NEO4J_TAG_REPOSITORY;
    }

    public static TagRepository mongoTagRepository() {
        return MONGO_TAG_REPOSITORY;
    }

    public static TagSetRepository postgresTagSetRepository() {
        return POSTGRES_TAG_SET_REPOSITORY;
    }

    public static TagSetRepository neo4jTagSetRepository() {
        return NEO4J_TAG_SET_REPOSITORY;
    }

    public static TagSetRepository mongoTagSetRepository() {
        return MONGO_TAG_SET_REPOSITORY;
    }

    public static Cortex cortex() {
        return CORTEX;
    }

    /** Switches {@code JavAITagging}'s single ambient "current backend" to Postgres -- see this class's own
     *  javadoc for why this is needed at all. Idempotent; cheap to call at the top of every Postgres tagging
     *  test method. */
    public static void activatePostgresTagging() {
        JavAITagging.configureTagging(POSTGRES_CONFIG, POSTGRES_TAG_REPOSITORY);
    }

    public static void activateNeo4jTagging() {
        JavAITagging.configureTagging(NEO4J_CONFIG, NEO4J_TAG_REPOSITORY);
    }

    public static void activateMongoTagging() {
        JavAITagging.configureTagging(MONGO_CONFIG, MONGO_TAG_REPOSITORY);
    }
}
