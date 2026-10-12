package dev.xtrafe.javai.persistence;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/** {@link FullTextSearchContract} on MongoDB: {@code $text} and trigrams in the {@code javai_fulltext} collection. */
@Testcontainers
class FullTextSearchMongoTest extends FullTextSearchContract {

    /** See {@code RepositoryBackendSpringDataMongoTest} for why the healthcheck wait is load-bearing here. */
    @Container
    static final GenericContainer<?> mongo =
            new GenericContainer<>(DockerImageName.parse("mongodb/mongodb-atlas-local:8.2"))
                    .withExposedPorts(27017)
                    .waitingFor(Wait.forHealthcheck())
                    .withStartupTimeout(Duration.ofMinutes(3));

    @Override
    JavAIPersistenceConfig config() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.MONGODB)
                .mongoUri("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/?directConnection=true")
                .mongoDatabase("javai_fulltext_test")
                .build();
    }

    @Override
    boolean narrowsVectorSearch() {
        return true;
    }
}
