package dev.xtrafe.javai.persistence;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** {@link FullTextSearchContract} on Postgres: {@code tsvector}, {@code pg_trgm}, in {@code javai_fulltext}. */
@Testcontainers
class FullTextSearchPostgresTest extends FullTextSearchContract {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Override
    JavAIPersistenceConfig config() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
    }

    @Override
    boolean narrowsVectorSearch() {
        return true;
    }
}
