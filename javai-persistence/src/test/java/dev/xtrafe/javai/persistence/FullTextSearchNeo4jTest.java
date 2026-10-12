package dev.xtrafe.javai.persistence;

import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** {@link FullTextSearchContract} on Neo4j: its native (Lucene) full-text index. */
@Testcontainers
class FullTextSearchNeo4jTest extends FullTextSearchContract {

    private static final String NEO4J_PASSWORD = "javai12345";

    @Container
    static final Neo4jContainer<?> neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26-community"))
            .withAdminPassword(NEO4J_PASSWORD);

    @Override
    JavAIPersistenceConfig config() {
        return JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.NEO4J)
                .neo4jUri(neo4j.getBoltUrl())
                .neo4jUsername("neo4j")
                .neo4jPassword(NEO4J_PASSWORD)
                .build();
    }

    @Override
    boolean narrowsVectorSearch() {
        return false;
    }
}
