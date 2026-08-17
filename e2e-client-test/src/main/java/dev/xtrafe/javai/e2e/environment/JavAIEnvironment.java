package dev.xtrafe.javai.e2e.environment;

import java.time.Duration;
import dev.xtrafe.javai.completion.Cortex;
import dev.xtrafe.javai.completion.LocalCompletionDefaults;
import dev.xtrafe.javai.e2e.domain.AnthologyRepository;
import dev.xtrafe.javai.e2e.domain.ArticleClusterRepository;
import dev.xtrafe.javai.e2e.domain.ArticleQueryRepository;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.AttachmentRepository;
import dev.xtrafe.javai.e2e.domain.CommentRepository;
import dev.xtrafe.javai.e2e.domain.LibraryRepository;
import dev.xtrafe.javai.e2e.domain.MediaNoteRepository;
import dev.xtrafe.javai.e2e.domain.PlaceRepository;
import dev.xtrafe.javai.e2e.domain.ShelfRepository;
import dev.xtrafe.javai.e2e.domain.assoc.AssocBiParentRepository;
import dev.xtrafe.javai.e2e.domain.assoc.AssocChainTopRepository;
import dev.xtrafe.javai.e2e.domain.assoc.AssocHubQueryRepository;
import dev.xtrafe.javai.e2e.domain.assoc.AssocHubRepository;
import dev.xtrafe.javai.e2e.domain.assoc.AssocLeafRepository;
import dev.xtrafe.javai.e2e.domain.assoc.AssocSelfNodeRepository;
import dev.xtrafe.javai.e2e.domain.assoc.PlainLeafRepository;
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

    /** How long this harness waits for the container-hosted model to generate -- see the CORTEX
     *  assignment below for why it is set here rather than in the connector. */
    private static final Duration LOCAL_INFERENCE_READ_TIMEOUT = Duration.ofMinutes(15);

    private static final ArticleRepository POSTGRES_ARTICLE_REPOSITORY;
    private static final ArticleRepository NEO4J_ARTICLE_REPOSITORY;
    private static final ArticleRepository MONGO_ARTICLE_REPOSITORY;

    // Neo4j-only: a KnowledgeGraph<Article, RelatesTo> field is Neo4j-only (see ArticleCluster's own
    // javadoc), so there's no Postgres/Mongo counterpart to build here.
    private static final ArticleClusterRepository NEO4J_ARTICLE_CLUSTER_REPOSITORY;

    private static final CommentRepository POSTGRES_COMMENT_REPOSITORY;
    private static final CommentRepository NEO4J_COMMENT_REPOSITORY;
    private static final CommentRepository MONGO_COMMENT_REPOSITORY;

    // OMI-141: a non-vectorized entity with a geo Point, one repository per backend (see DerivedFinderE2ETest).
    private static final PlaceRepository POSTGRES_PLACE_REPOSITORY;
    private static final PlaceRepository NEO4J_PLACE_REPOSITORY;
    private static final PlaceRepository MONGO_PLACE_REPOSITORY;

    // OMI-230: narrowed vector search. Postgres and Mongo only -- Neo4j refuses a narrowed query at
    // repository-creation time, so registering this there would fail this whole class's static init.
    private static final MediaNoteRepository POSTGRES_MEDIA_NOTE_REPOSITORY;
    private static final MediaNoteRepository MONGO_MEDIA_NOTE_REPOSITORY;

    private static final TagRepository POSTGRES_TAG_REPOSITORY;
    private static final TagRepository NEO4J_TAG_REPOSITORY;
    private static final TagRepository MONGO_TAG_REPOSITORY;

    private static final TagSetRepository POSTGRES_TAG_SET_REPOSITORY;
    private static final TagSetRepository NEO4J_TAG_SET_REPOSITORY;
    private static final TagSetRepository MONGO_TAG_SET_REPOSITORY;

    // OMI-161: the association-shape regression matrix (see AssocHub). Postgres only -- the bug was in
    // the Hibernate backend's proxy handling, and Hibernate proxies exist on no other backend.
    private static final AssocHubRepository POSTGRES_ASSOC_HUB_REPOSITORY;
    private static final AssocLeafRepository POSTGRES_ASSOC_LEAF_REPOSITORY;
    private static final PlainLeafRepository POSTGRES_PLAIN_LEAF_REPOSITORY;
    private static final AssocChainTopRepository POSTGRES_ASSOC_CHAIN_TOP_REPOSITORY;
    private static final AssocSelfNodeRepository POSTGRES_ASSOC_SELF_NODE_REPOSITORY;
    private static final AssocBiParentRepository POSTGRES_ASSOC_BI_PARENT_REPOSITORY;

    private static final AnthologyRepository POSTGRES_ANTHOLOGY_REPOSITORY;
    private static final ShelfRepository POSTGRES_SHELF_REPOSITORY;
    private static final LibraryRepository POSTGRES_LIBRARY_REPOSITORY;
    private static final AnthologyRepository NEO4J_ANTHOLOGY_REPOSITORY;
    private static final AnthologyRepository MONGO_ANTHOLOGY_REPOSITORY;

    /** Exposed so a test can compose several repository calls into one unit of work -- which a test that
     *  traverses a lazy association must, since a repository returns a detached entity (OMI-271). */
    /** Declared queries (OMI-398) and `@Any` predicates (OMI-407) -- Postgres-only interfaces, deliberately
     *  separate from the repositories realized against all three backends. */
    private static final ArticleQueryRepository POSTGRES_ARTICLE_QUERY_REPOSITORY;
    private static final AssocHubQueryRepository POSTGRES_ASSOC_HUB_QUERY_REPOSITORY;

    private static final JavAIPersistenceConfig POSTGRES_CONFIG;

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
        POSTGRES_CONFIG = postgresConfig;
        POSTGRES_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class, postgresConfig);
        POSTGRES_PLACE_REPOSITORY = JavAIPI.repository(PlaceRepository.class, postgresConfig);
        POSTGRES_MEDIA_NOTE_REPOSITORY = JavAIPI.repository(MediaNoteRepository.class, postgresConfig);
        POSTGRES_COMMENT_REPOSITORY = JavAIPI.repository(CommentRepository.class, postgresConfig);
        POSTGRES_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class, postgresConfig);
        POSTGRES_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class, postgresConfig);
        // AssocLeaf/AssocChainMiddle/AssocBiChild are auto-registered as related types, but each root that
        // tests query independently still needs its own proxy.
        POSTGRES_ANTHOLOGY_REPOSITORY = JavAIPI.repository(AnthologyRepository.class, postgresConfig);
        POSTGRES_SHELF_REPOSITORY = JavAIPI.repository(ShelfRepository.class, postgresConfig);
        POSTGRES_LIBRARY_REPOSITORY = JavAIPI.repository(LibraryRepository.class, postgresConfig);
        POSTGRES_ASSOC_HUB_REPOSITORY = JavAIPI.repository(AssocHubRepository.class, postgresConfig);
        // A @Query is refused when the repository is REALIZED, and only Postgres serves one -- so these two
        // interfaces exist solely to keep declared queries off ArticleRepository/AssocHubRepository, which
        // are (or could be) realized against the other two backends. See ArticleQueryRepository's javadoc.
        POSTGRES_ARTICLE_QUERY_REPOSITORY = JavAIPI.repository(ArticleQueryRepository.class, postgresConfig);
        POSTGRES_ASSOC_HUB_QUERY_REPOSITORY = JavAIPI.repository(AssocHubQueryRepository.class, postgresConfig);
        POSTGRES_ASSOC_LEAF_REPOSITORY = JavAIPI.repository(AssocLeafRepository.class, postgresConfig);
        POSTGRES_PLAIN_LEAF_REPOSITORY = JavAIPI.repository(PlainLeafRepository.class, postgresConfig);
        POSTGRES_ASSOC_CHAIN_TOP_REPOSITORY = JavAIPI.repository(AssocChainTopRepository.class, postgresConfig);
        POSTGRES_ASSOC_SELF_NODE_REPOSITORY = JavAIPI.repository(AssocSelfNodeRepository.class, postgresConfig);
        POSTGRES_ASSOC_BI_PARENT_REPOSITORY = JavAIPI.repository(AssocBiParentRepository.class, postgresConfig);

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
        NEO4J_PLACE_REPOSITORY = JavAIPI.repository(PlaceRepository.class, neo4jConfig);
        // Article (a KnowledgeGraph node type) is already registered above -- required before Neo4j's label
        // registry can resolve nodes reached through ArticleCluster.graph during hydration.
        NEO4J_ARTICLE_CLUSTER_REPOSITORY = JavAIPI.repository(ArticleClusterRepository.class, neo4jConfig);
        NEO4J_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class, neo4jConfig);
        NEO4J_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class, neo4jConfig);
        // Article/Comment are already registered above -- Anthology's own member fields reach both.
        NEO4J_ANTHOLOGY_REPOSITORY = JavAIPI.repository(AnthologyRepository.class, neo4jConfig);

        // No CommentRepository/AttachmentRepository pre-registration here either: RepositoryBackendSpringDataMongo
        // recursively auto-registers related types too, matching Postgres's convenience rather than Neo4j's
        // explicit-registration requirement.
        JavAIPersistenceConfig mongoConfig = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri(MonolithicContainer.mongoUri())
                .mongoDatabase("javai")
                .build();
        MONGO_ARTICLE_REPOSITORY = JavAIPI.repository(ArticleRepository.class, mongoConfig);
        MONGO_PLACE_REPOSITORY = JavAIPI.repository(PlaceRepository.class, mongoConfig);
        MONGO_MEDIA_NOTE_REPOSITORY = JavAIPI.repository(MediaNoteRepository.class, mongoConfig);
        MONGO_COMMENT_REPOSITORY = JavAIPI.repository(CommentRepository.class, mongoConfig);
        MONGO_TAG_REPOSITORY = JavAIPI.repository(TagRepository.class, mongoConfig);
        MONGO_TAG_SET_REPOSITORY = JavAIPI.repository(TagSetRepository.class, mongoConfig);
        MONGO_ANTHOLOGY_REPOSITORY = JavAIPI.repository(AnthologyRepository.class, mongoConfig);

        // Willing to wait, because in this harness a slow answer is real work rather than a stall: the model
        // runs on CPU inside the test container, and a long classification prompt legitimately takes minutes
        // to generate. The default timeout severs that mid-generation, which surfaced as
        // TaggingE2ETest.classifyAll... failing with "Read timed out" while the other twelve tagging tests
        // passed against the very same endpoint. Deliberately set here, in test infrastructure, and NOT in
        // the connector's own defaults -- a production caller must keep a timeout that reports a provider
        // which has genuinely stopped answering.
        CORTEX = LocalCompletionDefaults.create(
                MonolithicContainer.completionEndpoint(), LOCAL_INFERENCE_READ_TIMEOUT);

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

    /** The Postgres configuration, for {@link JavAIPI#inTransaction} -- see {@link #POSTGRES_CONFIG}. */
    public static ArticleQueryRepository postgresArticleQueryRepository() {
        return POSTGRES_ARTICLE_QUERY_REPOSITORY;
    }

    public static AssocHubQueryRepository postgresAssocHubQueryRepository() {
        return POSTGRES_ASSOC_HUB_QUERY_REPOSITORY;
    }

    public static JavAIPersistenceConfig postgresConfig() {
        return POSTGRES_CONFIG;
    }

    public static ArticleRepository neo4jArticleRepository() {
        return NEO4J_ARTICLE_REPOSITORY;
    }

    public static ArticleRepository mongoArticleRepository() {
        return MONGO_ARTICLE_REPOSITORY;
    }

    public static ArticleClusterRepository neo4jArticleClusterRepository() {
        return NEO4J_ARTICLE_CLUSTER_REPOSITORY;
    }

    public static PlaceRepository postgresPlaceRepository() {
        return POSTGRES_PLACE_REPOSITORY;
    }

    public static PlaceRepository neo4jPlaceRepository() {
        return NEO4J_PLACE_REPOSITORY;
    }

    /** OMI-230's narrowed vector search. No Neo4j counterpart, deliberately -- that backend refuses a
     *  narrowed query rather than approximating it; see {@code MediaNoteRepository}. */
    public static MediaNoteRepository postgresMediaNoteRepository() {
        return POSTGRES_MEDIA_NOTE_REPOSITORY;
    }

    public static MediaNoteRepository mongoMediaNoteRepository() {
        return MONGO_MEDIA_NOTE_REPOSITORY;
    }

    public static PlaceRepository mongoPlaceRepository() {
        return MONGO_PLACE_REPOSITORY;
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

    public static AssocHubRepository postgresAssocHubRepository() {
        return POSTGRES_ASSOC_HUB_REPOSITORY;
    }

    public static AssocLeafRepository postgresAssocLeafRepository() {
        return POSTGRES_ASSOC_LEAF_REPOSITORY;
    }

    public static PlainLeafRepository postgresPlainLeafRepository() {
        return POSTGRES_PLAIN_LEAF_REPOSITORY;
    }

    public static AssocChainTopRepository postgresAssocChainTopRepository() {
        return POSTGRES_ASSOC_CHAIN_TOP_REPOSITORY;
    }

    public static AssocSelfNodeRepository postgresAssocSelfNodeRepository() {
        return POSTGRES_ASSOC_SELF_NODE_REPOSITORY;
    }

    public static AssocBiParentRepository postgresAssocBiParentRepository() {
        return POSTGRES_ASSOC_BI_PARENT_REPOSITORY;
    }

    public static AnthologyRepository postgresAnthologyRepository() {
        return POSTGRES_ANTHOLOGY_REPOSITORY;
    }

    public static ShelfRepository postgresShelfRepository() {
        return POSTGRES_SHELF_REPOSITORY;
    }

    public static LibraryRepository postgresLibraryRepository() {
        return POSTGRES_LIBRARY_REPOSITORY;
    }

    public static AnthologyRepository neo4jAnthologyRepository() {
        return NEO4J_ANTHOLOGY_REPOSITORY;
    }

    public static AnthologyRepository mongoAnthologyRepository() {
        return MONGO_ANTHOLOGY_REPOSITORY;
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
