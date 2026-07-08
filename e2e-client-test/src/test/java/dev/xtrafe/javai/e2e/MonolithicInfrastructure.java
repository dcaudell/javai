package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.runtime.LocalEmbeddingDefaults;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The one, shared piece of infrastructure for this whole e2e project: a single monolithic container
 * (built from {@code docker/Dockerfile}) bundling Postgres+pgvector, Neo4j, and Ollama -- with the
 * reference embedding model already baked into the image -- as three {@code supervisord}-managed
 * processes, rather than one Testcontainers container per service. Every e2e test class in this project
 * shares this same running container instead of each starting its own.
 *
 * <p>Testcontainers' singleton-container idiom: a {@code static final} field, started once via a static
 * initializer the first test class to reference this class triggers, never stopped explicitly --
 * Testcontainers' own Ryuk resource reaper cleans it up when the JVM exits. Subsequent references from
 * other test classes in the same JVM see the already-running container, since a class's static
 * initializer only ever runs once.
 *
 * <p>Baking Ollama into the image (rather than using {@link LocalEmbeddingDefaults}' own
 * platform-dependent TEI/Ollama container choice) fixes the embedding backend for this whole project to
 * Ollama, unconditionally -- so the first thing this class does is force
 * {@link LocalEmbeddingDefaults#OVERRIDE_PROPERTY} to {@code ollama}, before the container even starts,
 * so every other call into {@code LocalEmbeddingDefaults} (its own {@code modelLabel()}/{@code create(...)}
 * factory) agrees with what's actually running.
 */
final class MonolithicInfrastructure {

    private static final int POSTGRES_PORT = 5432;
    private static final int NEO4J_HTTP_PORT = 7474;
    private static final int NEO4J_BOLT_PORT = 7687;
    private static final int OLLAMA_PORT = 11434;

    static final GenericContainer<?> CONTAINER = start();

    private MonolithicInfrastructure() {
    }

    private static GenericContainer<?> start() {
        System.setProperty(LocalEmbeddingDefaults.OVERRIDE_PROPERTY, "ollama");

        GenericContainer<?> container = new GenericContainer<>(new ImageFromDockerfile()
                .withDockerfile(Path.of("docker/Dockerfile")))
                .withExposedPorts(POSTGRES_PORT, NEO4J_HTTP_PORT, NEO4J_BOLT_PORT, OLLAMA_PORT)
                .waitingFor(Wait.forListeningPorts(POSTGRES_PORT, NEO4J_HTTP_PORT, NEO4J_BOLT_PORT, OLLAMA_PORT)
                        .withStartupTimeout(Duration.ofMinutes(10)));
        container.start();
        return container;
    }

    static URI embeddingEndpoint() {
        return URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(OLLAMA_PORT));
    }

    static String postgresUrl() {
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(POSTGRES_PORT) + "/javai";
    }

    static String neo4jUri() {
        return "bolt://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(NEO4J_BOLT_PORT);
    }
}
