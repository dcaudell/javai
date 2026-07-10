package dev.xtrafe.javai.e2e;

import dev.xtrafe.javai.e2e.fixtures.SampleDataSeeder;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.LocalEmbeddingDefaults;
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
 * initializer the first test class to reference this class triggers, never stopped explicitly. Subsequent
 * references from other test classes in the same JVM see the already-running container, since a class's
 * static initializer only ever runs once.
 *
 * <p><b>Unlike every other Testcontainers-managed container in this reactor, this one is deliberately
 * persistent across separate {@code mvn test} invocations, not just within one JVM</b> -- {@code
 * .withReuse(true)} below, which excludes it from Ryuk's cleanup entirely (Testcontainers' own documented
 * behavior: a reuse-marked container is meant to survive the JVM exiting, so the next run attaches to the
 * same one instead of paying cold start again -- the expensive image build and model pull. Domain data is
 * deliberately NOT left persistent across runs, though: {@link SampleDataSeeder} truncates and reseeds it
 * every time this class starts, since every pre-existing e2e test assumes a fresh database (see that
 * class's own javadoc for why). This requires never calling {@code .stop()}/{@code .close()} on it, which
 * this class already never does. Real, one-time cost of this trade-off, stated plainly: a `docker/Dockerfile` edit does
 * <em>not</em> automatically take effect on the next run anymore -- the persisted container must be
 * removed by hand (see this module's README) to force a rebuild. Reuse itself is opt-in per machine, off
 * by default (Testcontainers won't honor {@code withReuse(true)} without
 * {@code ~/.testcontainers.properties} containing {@code testcontainers.reuse.enable=true} -- see the
 * README for why this can't be a project-committed file).
 *
 * <p>The built image is explicitly named {@code javai-e2e-monolithic} rather than left to its default -- a
 * random {@code localhost/testcontainers/<16-char-random-string>} tag -- so a leftover from an interrupted
 * build is identifiable in {@code docker images}/Docker Desktop instead of showing up as an unlabelled
 * {@code <none>:<none>} dangling image.
 *
 * <p><b>{@code ImageFromDockerfile}'s own {@code deleteOnExit} must also be {@code false}</b> (the
 * {@code (String, boolean)} constructor, not the {@code (String)} one) -- confirmed empirically, not
 * assumed: {@code .withReuse(true)} alone (verified working in isolation against a plain
 * {@code GenericContainer}) still wasn't enough here, because {@code ImageFromDockerfile}'s default
 * {@code deleteOnExit=true} tears the underlying *image* down on JVM exit regardless of the container-level
 * reuse setting, which takes the container relying on that image down with it. Both flags have to agree
 * ("don't delete the image" and "don't delete the container") for the container to actually survive.
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

        GenericContainer<?> container = new GenericContainer<>(
                new ImageFromDockerfile("javai-e2e-monolithic", false)
                .withDockerfile(Path.of("docker/Dockerfile")))
                .withExposedPorts(POSTGRES_PORT, NEO4J_HTTP_PORT, NEO4J_BOLT_PORT, OLLAMA_PORT)
                .withReuse(true)
                .waitingFor(Wait.forListeningPorts(POSTGRES_PORT, NEO4J_HTTP_PORT, NEO4J_BOLT_PORT, OLLAMA_PORT)
                        .withStartupTimeout(Duration.ofMinutes(10)));
        container.start();

        // CONTAINER isn't assigned yet at this point (this whole method is CONTAINER's own initializer),
        // so the URLs SampleDataSeeder needs are built from the local reference, via the same private
        // helpers postgresUrl()/neo4jUri() below delegate to once CONTAINER is set. The embedding provider
        // must be configured before seeding too -- normally each test class's own @BeforeAll does this,
        // but seeding runs earlier than any of them (inside this static initializer).
        URI embeddingEndpoint = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(OLLAMA_PORT));
        JavAIRuntime.configureEmbeddingProvider(LocalEmbeddingDefaults.create(embeddingEndpoint));
        SampleDataSeeder.resetAndSeed(postgresUrl(container), neo4jUri(container));
        return container;
    }

    static URI embeddingEndpoint() {
        return URI.create("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(OLLAMA_PORT));
    }

    /** Same URI as {@link #embeddingEndpoint()} -- one Ollama instance in this container serves both the
     *  embedding model and the chat/completion model (see {@code docker/Dockerfile}); a distinctly-named
     *  accessor for call-site clarity in completion tests, not a second container or port. */
    static URI completionEndpoint() {
        return embeddingEndpoint();
    }

    static String postgresUrl() {
        return postgresUrl(CONTAINER);
    }

    static String neo4jUri() {
        return neo4jUri(CONTAINER);
    }

    private static String postgresUrl(GenericContainer<?> container) {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(POSTGRES_PORT) + "/javai";
    }

    private static String neo4jUri(GenericContainer<?> container) {
        return "bolt://" + container.getHost() + ":" + container.getMappedPort(NEO4J_BOLT_PORT);
    }
}
