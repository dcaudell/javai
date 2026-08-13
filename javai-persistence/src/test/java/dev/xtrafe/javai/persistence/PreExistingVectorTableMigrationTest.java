package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code javai_vectors__<model>} table created before OMI-290 must gain {@code computed_for} on first
 * use, rather than failing the write.
 *
 * <p>This is a regression test for a migration that could not run. {@code ensureFieldVectorTable} shipped
 * the {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} for exactly this case, but called
 * {@code provisionTable} without naming the column as required -- so {@code isAlreadyProvisioned} saw a
 * table that existed, reported it provisioned, and skipped the entire DDL block including the migration.
 * Every fresh database hid it: only an upgraded one reaches the broken path, and until
 * {@code e2e-client-test}'s persistent container accidentally became one, nothing in this repository ever
 * did.
 *
 * <p>The table is created here by hand, in its exact pre-OMI-290 shape, because that is the only way to
 * produce the state under test -- the current code cannot create the old table for us.
 */
@Testcontainers
class PreExistingVectorTableMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void aVectorTableCreatedBeforeOmi290GainsComputedForOnFirstUse() throws SQLException {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        String table = "javai_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        createPreOmi290Table(table);

        TestArticleRepository articles = JavAIPI.repository(TestArticleRepository.class,
                JavAIPersistenceConfig.builder()
                        .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                        .postgresUrl(postgres.getJdbcUrl())
                        .postgresUsername(postgres.getUsername())
                        .postgresPassword(postgres.getPassword())
                        .build());

        // The write is the assertion: against the unmigrated table this threw
        // `column "computed_for" does not exist`.
        articles.save(new TestArticle("Upgrade path", "A row written by the current library."));
        assertTrue(hasComputedFor(table), "first use must migrate a pre-OMI-290 table, not skip it");
    }

    private void createPreOmi290Table(String table) throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE " + table + " ("
                    + "owner_type   varchar(255) NOT NULL,"
                    + "owner_id     uuid         NOT NULL,"
                    + "field_name   varchar(128) NOT NULL,"
                    + "model_id     varchar(128) NOT NULL,"
                    + "dims         integer      NOT NULL,"
                    + "vector       vector(" + FakeEmbeddingProvider.DIMS + ") NOT NULL,"
                    + "computed_at  timestamptz  NOT NULL,"
                    + "PRIMARY KEY (owner_type, owner_id, field_name))");
        }
    }

    private boolean hasComputedFor(String table) throws SQLException {
        try (Connection connection = connect();
                ResultSet columns = connection.getMetaData().getColumns(null, null, table, "computed_for")) {
            return columns.next();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
