package dev.xtrafe.javai.e2e.fixtures;

import dev.xtrafe.javai.e2e.domain.Article;
import dev.xtrafe.javai.e2e.domain.ArticleRepository;
import dev.xtrafe.javai.e2e.domain.Comment;
import dev.xtrafe.javai.e2e.environment.MonolithicContainer;
import net.datafaker.Faker;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Resets and re-seeds {@link MonolithicContainer}'s persistent container with ample, real (not
 * random-noise) sample data on every run. Moved here (main code, {@link dev.xtrafe.javai.e2e.environment}'s
 * sibling package) from test code, per the same reasoning as that package: mock-data seeding is a real
 * responsibility of the e2e application's own environment setup, not test-owned scaffolding -- see {@code
 * JavAIEnvironment}, the one caller.
 *
 * <p><b>Domain data does NOT persist across runs, even though the container/image now does.</b> An
 * earlier version of this class only seeded once (skipping if the Postgres table was non-empty), on the
 * assumption that "persistent container" meant "persistent data too." Confirmed empirically to be a real
 * bug, not a theoretical risk: every pre-existing e2e test (e.g. {@code PersistenceE2ETest},
 * {@code ArticleFixtureVolumeE2ETest}) inserts its own hand-written fixtures fresh on every run, with no
 * deduplication -- always safe before, because the whole database reset with every fresh container. Once
 * the container survived, those fixtures started accumulating as near-duplicate rows across runs (confirmed
 * via a direct query: the same fixture title existed twice after a second run), and two independently
 * computed embeddings of the *exact same text* can be close enough to each other -- real floating-point
 * non-determinism in Ollama's own CPU inference, already documented elsewhere in this project, see
 * {@code ArticleFixtureVolumeE2ETest}'s own javadoc -- that a test's own fresh self-match can occasionally
 * lose to the older duplicate. Resetting domain data every run (this class truncates every Postgres table
 * and wipes the whole Neo4j graph before reseeding) keeps every existing test's original "fresh database"
 * assumption intact while still keeping the expensive part -- the container/image itself, and the
 * multi-gigabyte model pull baked into it -- persistent across runs.
 *
 * <p>Uses <a href="https://www.datafaker.net/">DataFaker</a>, not Instancio or similar POJO-graph
 * generators: this project's existing fixture data ({@link ArticleFixtures}) is deliberately real,
 * topically-clustered English text, since semantic-similarity tests need real embeddable signal, not
 * type-correct-but-meaningless random values. DataFaker generates realistic-<em>sounding</em> domain
 * content from real English vocabulary instead.
 *
 * <p><b>Topics deliberately do NOT mirror {@link ArticleFixtures}'s own four (Cybersecurity, Cooking,
 * Sports, Space).</b> An earlier version of this class used those same four (Hacker/Food/Football/Space
 * providers) specifically to mirror that fixture's topic-clustering idea -- confirmed empirically to be a
 * mistake too: it caused a real, reproducible failure in a Neo4j self-rank test, whose own reference
 * article ("Zero-day disclosed in widely used TLS library") sat close enough to this class's seeded
 * Hacker-topic articles, under real embedding noise, to occasionally outrank the reference's own
 * self-match. Business (Company), Music, Currency, and Color were chosen instead specifically to minimize
 * semantic overlap with every existing hand-written fixture topic elsewhere in this suite -- reducing, not
 * eliminating, the inherent noise-floor risk of a large real corpus occasionally perturbing a strict
 * self-rank assertion.
 */
public final class SampleDataSeeder {

    private static final int ARTICLE_COUNT = 50;

    private SampleDataSeeder() {
    }

    /**
     * Truncates all Postgres tables and wipes the Neo4j graph, then seeds both fresh -- every run, not just
     * the first, since domain data is deliberately not persisted across runs (see class javadoc).
     *
     * <p>Takes already-built repositories rather than constructing its own: {@code JavAIEnvironment} is now
     * the single place that calls {@code JavAIPI.configurePersistence}/{@code JavAIPI.repository}, so this
     * class only needs the raw JDBC/Bolt URLs for the truncate/wipe step (not part of the {@code
     * JavAIRepository} contract) and the two already-realized repositories for the actual seeding.
     */
    public static void resetAndSeed(
            String postgresUrl, String neo4jUri, ArticleRepository postgresRepository, ArticleRepository neo4jRepository) {
        resetPostgres(postgresUrl);
        resetNeo4j(neo4jUri);

        for (Article article : generateArticles()) {
            postgresRepository.save(article);
            neo4jRepository.save(article);
        }
    }

    private static final int READINESS_RETRY_ATTEMPTS = 20;
    private static final long READINESS_RETRY_DELAY_MILLIS = 1000;

    /** Discovers every table in the {@code public} schema (rather than hardcoding names) so this doesn't
     *  quietly break if the per-model vector table names change (see {@code HibernatePostgresRepositoryBackend}'s
     *  own table-per-model scheme). */
    private static void resetPostgres(String postgresUrl) {
        try (Connection connection = connectToPostgresWithRetry(postgresUrl)) {
            List<String> tableNames = new ArrayList<>();
            try (Statement listTables = connection.createStatement();
                    ResultSet tables = listTables.executeQuery(
                            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
                while (tables.next()) {
                    tableNames.add(tables.getString("tablename"));
                }
            }
            if (!tableNames.isEmpty()) {
                try (Statement truncate = connection.createStatement()) {
                    truncate.execute("TRUNCATE TABLE " + String.join(", ", tableNames) + " CASCADE");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset Postgres domain data before seeding", e);
        }
    }

    /**
     * {@link MonolithicContainer#ensureRunning()}'s own readiness check only confirms Postgres's TCP port
     * is accepting connections at all -- confirmed empirically to be not the same thing as Postgres being
     * ready to actually complete the wire-protocol handshake for a real client on a just-created container:
     * supervisord considers the process "RUNNING" the moment it's stayed alive for a second, well before
     * Postgres has finished its own internal startup sequence, and a connection attempt in that window gets
     * a raw {@code SQLException}/connection-reset rather than a clean refusal. Retrying the actual JDBC
     * handshake here, not just the TCP port, is what's actually needed -- this is a real, observed race,
     * not a defensive guess.
     */
    private static Connection connectToPostgresWithRetry(String postgresUrl) throws SQLException {
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= READINESS_RETRY_ATTEMPTS; attempt++) {
            try {
                return DriverManager.getConnection(
                        postgresUrl, MonolithicContainer.POSTGRES_USERNAME, MonolithicContainer.POSTGRES_PASSWORD);
            } catch (SQLException e) {
                lastFailure = e;
                sleep(READINESS_RETRY_DELAY_MILLIS);
            }
        }
        throw lastFailure;
    }

    private static void resetNeo4j(String neo4jUri) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= READINESS_RETRY_ATTEMPTS; attempt++) {
            try (Driver driver = GraphDatabase.driver(neo4jUri,
                    AuthTokens.basic(MonolithicContainer.NEO4J_USERNAME, MonolithicContainer.NEO4J_PASSWORD))) {
                driver.verifyConnectivity();
                try (Session session = driver.session()) {
                    session.run("MATCH (n) DETACH DELETE n").consume();
                }
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                sleep(READINESS_RETRY_DELAY_MILLIS);
            }
        }
        throw lastFailure;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the database to become ready", e);
        }
    }

    private static List<Article> generateArticles() {
        Faker faker = new Faker();
        Random random = new Random();
        List<Function<Faker, Article>> generators = List.of(
                SampleDataSeeder::businessArticle,
                SampleDataSeeder::musicArticle,
                SampleDataSeeder::currencyArticle,
                SampleDataSeeder::colorArticle);

        List<Article> articles = new ArrayList<>(ARTICLE_COUNT);
        for (int i = 0; i < ARTICLE_COUNT; i++) {
            Article article = generators.get(i % generators.size()).apply(faker);
            int commentCount = 1 + random.nextInt(3);
            for (int c = 0; c < commentCount; c++) {
                article.getComments().add(new Comment(faker.name().fullName(), faker.lorem().sentence(10)));
            }
            articles.add(article);
        }
        return articles;
    }

    private static Article businessArticle(Faker faker) {
        String title = faker.company().name() + " announces new strategy";
        String body = faker.company().catchPhrase() + "; that is the new direction for the "
                + faker.company().industry() + " industry.";
        return new Article(title, body);
    }

    private static Article musicArticle(Faker faker) {
        String title = "Venue announces " + faker.music().genre() + " night featuring " + faker.music().instrument();
        String body = "The show will highlight " + faker.music().chord() + " progressions in the "
                + faker.music().key() + " key, performed live on " + faker.music().instrument() + ".";
        return new Article(title, body);
    }

    private static Article currencyArticle(Faker faker) {
        String title = "Analysts discuss the " + faker.currency().name() + " outlook";
        String body = "Traders are watching the " + faker.currency().code() + " exchange rate closely as a key "
                + "indicator this quarter.";
        return new Article(title, body);
    }

    private static Article colorArticle(Faker faker) {
        String title = "Design trends embrace " + faker.color().name();
        String body = "The shade, close to " + faker.color().hex() + ", is showing up across home decor and "
                + "fashion this season.";
        return new Article(title, body);
    }
}
