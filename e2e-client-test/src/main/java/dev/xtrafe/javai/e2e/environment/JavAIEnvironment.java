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
import dev.xtrafe.javai.tagging.JavAITagRepository;
import dev.xtrafe.javai.tagging.TagRepository;
import dev.xtrafe.javai.tagging.TagSetRepository;
import dev.xtrafe.javai.vector.LocalEmbeddingDefaults;

/**
 * The single class every e2e test class references to get an already-configured environment -- the
 * embedding provider, all three persistence backends' {@code ArticleRepository}/{@code CommentRepository}/
 * {@code TagRepository}/{@code TagSetRepository}/{@code JavAITagRepository}, a {@code Cortex}, and seeded
 * mock data, all wired here instead of duplicated across test classes' own {@code @BeforeAll} methods (as
 * they used to be, each independently calling {@code JavAIRuntime.configureEmbeddingProvider(...)}, and two
 * of them also independently calling {@code JavAIPI.repository(...)} with hardcoded credentials).
 *
 * <p>Initialization happens exactly once per JVM, in the static initializer below -- the same "static field
 * triggers on first reference" idiom this project's predecessor, {@code MonolithicInfrastructure}, already
 * used. A test class's {@code @BeforeAll} should just call {@link #ensureRunning()}: an intentionally
 * no-op method whose only purpose is to force this class to load (and thus run its static initializer) at
 * an explicit, visible point, rather than relying on some other incidental reference to trigger it.
 *
 * <p>{@link JavAIPI#repository(Class, JavAIPersistenceConfig)} takes its config explicitly and returns a
 * proxy permanently bound to it -- no ambient "current config" to switch. That's what lets this class
 * safely build and hand out a Postgres-backed, a Neo4j-backed, <em>and</em> a MongoDB-backed
 * {@code ArticleRepository} here, once, for every test class to share, rather than each test needing to
 * reconstruct its own.
 *
 * <p>{@link JavAITagRepository} follows the identical shape -- each of {@link #postgresTagging()}/
 * {@link #neo4jTagging()}/{@link #mongoTagging()} is a permanently-bound instance built once below, wrapping
 * that backend's own {@code TagRepository} and the shared {@link #cortex()}. There is no ambient "current
 * tagging backend" anywhere: a caller wanting Neo4j tagging just calls {@link #neo4jTagging()} and uses the
 * instance it gets back, the same way it already calls {@link #neo4jArticleRepository()}.
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

    private static final Cortex CORTEX;

    private static final JavAITagRepository POSTGRES_TAGGING;
    private static final JavAITagRepository NEO4J_TAGGING;
    private static final JavAITagRepository MONGO_TAGGING;

    static {
        MonolithicContainer.ensureRunning();
        JavAIRuntime.configureEmbeddingProvider(LocalEmbeddingDefaults.create(MonolithicContainer.embeddingEndpoint()));

        // No CommentRepository/AttachmentRepository pre-registration here: RepositoryBackendHibernatePostgres
        // auto-registers both, recursively, as soon as ArticleRepository is realized -- reachable through
        // Article's own featuredComment/draftComment/attachment/comments/relatedComments fields. Tag/TagSet
        // are unrelated to Article, though, so still need their own explicit repository() call to get a
        // usable proxy, same as CommentRepository always has.
        JavAIPersistenceConfig postgresConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(MonolithicContainer.postgresUrl())
                .postgresUsername(MonolithicContainer.POSTGRES_USERNAME)
                .postgresPassword(MonolithicContainer.POSTGRES_PASSWORD)
                .build();
        POSTGRES_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class, postgresConfig);
        POSTGRES_COMMENT_REPOSITORY = JavAIPI.repository(CommentRepository.class, postgresConfig);
        POSTGRES_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class, postgresConfig);
        POSTGRES_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class, postgresConfig);

        // Neo4j still needs explicit registration for every independently-queried type -- only the Postgres
        // backend's related-type auto-registration has been automated so far.
        JavAIPersistenceConfig neo4jConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(MonolithicContainer.neo4jUri())
                .neo4jUsername(MonolithicContainer.NEO4J_USERNAME)
                .neo4jPassword(MonolithicContainer.NEO4J_PASSWORD)
                .build();
        NEO4J_COMMENT_REPOSITORY = JavAIPI.repository(CommentRepository.class, neo4jConfig);
        JavAIPI.repository(AttachmentRepository.class, neo4jConfig);
        NEO4J_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class, neo4jConfig);
        NEO4J_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class, neo4jConfig);
        NEO4J_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class, neo4jConfig);

        // No CommentRepository/AttachmentRepository pre-registration here either: RepositoryBackendSpringDataMongo
        // recursively auto-registers related types too, matching Postgres's convenience rather than Neo4j's
        // explicit-registration requirement.
        JavAIPersistenceConfig mongoConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri(MonolithicContainer.mongoUri())
                .mongoDatabase("javai")
                .build();
        MONGO_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class, mongoConfig);
        MONGO_COMMENT_REPOSITORY = JavAIPI.repository(CommentRepository.class, mongoConfig);
        MONGO_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class, mongoConfig);
        MONGO_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class, mongoConfig);

        CORTEX = LocalCompletionDefaults.create(MonolithicContainer.completionEndpoint());

        // Built once, after CORTEX exists -- JavAITagRepository takes its Cortex at construction, not via a
        // settable mutator (see that class's own javadoc), so it has to come after CORTEX is ready.
        POSTGRES_TAGGING = new JavAITagRepository(POSTGRES_TAG_REPOSITORY, postgresConfig, CORTEX);
        NEO4J_TAGGING = new JavAITagRepository(NEO4J_TAG_REPOSITORY, neo4jConfig, CORTEX);
        MONGO_TAGGING = new JavAITagRepository(MONGO_TAG_REPOSITORY, mongoConfig, CORTEX);

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

    public static JavAITagRepository postgresTagging() {
        return POSTGRES_TAGGING;
    }

    public static JavAITagRepository neo4jTagging() {
        return NEO4J_TAGGING;
    }

    public static JavAITagRepository mongoTagging() {
        return MONGO_TAGGING;
    }
}
