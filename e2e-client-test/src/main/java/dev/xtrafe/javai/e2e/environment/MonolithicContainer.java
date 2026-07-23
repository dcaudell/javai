package dev.xtrafe.javai.e2e.environment;

import dev.xtrafe.javai.vector.LocalEmbeddingDefaults;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Owns the monolithic e2e container's lifecycle by talking to the {@code docker} CLI directly via {@link
 * ProcessBuilder} -- deliberately not Testcontainers. Testcontainers' {@code ImageFromDockerfile} +
 * {@code GenericContainer.withReuse(true)} (this class's predecessor, {@code MonolithicInfrastructure})
 * re-triggered a real {@code docker build} on every JVM run and matched "should I reuse the existing
 * container" by the *resulting image's hash* -- so any routine cache invalidation (this Dockerfile pulls
 * unpinned {@code apt}/{@code ollama} content, drifting over a multi-day project) silently started a
 * *second* container next to the still-running first one, which {@code withReuse} then exempted from
 * cleanup forever. Confirmed, not theoretical: one real run of this project accumulated 44 dangling images
 * (many full ~17.6GB monolithic builds) and ended up with a running container on a different image than the
 * one actually tagged {@code javai-e2e-monolithic:latest}.
 *
 * <p>This class matches by container <b>name</b> instead, which is what actually makes reuse robust: {@link
 * #ensureRunning()} checks {@code docker ps}/{@code docker ps -a} for a container named {@value
 * #CONTAINER_NAME} first, and only builds/runs a new one if truly absent. A rebuild is never triggered by
 * routine cache drift -- only by the container not existing at all. Ports are fixed (not Testcontainers-style
 * dynamic mapping) since there's now exactly one long-lived instance of this container per machine, not one
 * per test run.
 *
 * <p><b>Same trade-off as the predecessor, restated, not fixed</b>: editing {@code docker/Dockerfile} does
 * not automatically invalidate an already-running container. Force a rebuild by removing it yourself:
 * {@code docker rm -f javai-e2e-monolithic}. Hashing the Dockerfile's actual content to auto-detect this
 * was judged not worth the complexity, same call the predecessor made.
 *
 * <p><b>MongoDB is a second, separate container, not a fourth process folded into the monolith.</b> The
 * real {@code $vectorSearch} support this project needs comes from {@code mongodb/mongodb-atlas-local},
 * which bundles {@code mongod} and a second internal process ({@code mongot}) that talk to each other
 * through orchestration logic baked into that image's own entrypoint -- a compiled Go binary
 * ({@code /usr/local/bin/runner server}) with no public source. An attempt to hand-roll that same
 * mongod+mongot pairing inside this project's own monolithic image (curl-installing {@code mongot} and
 * driving it with the same flags the official image uses) was tried and abandoned: {@code mongot}
 * crash-looped with {@code IllegalArgumentException: The connection string contains an invalid host and
 * port}, thrown from an internal, undocumented bootstrap class ({@code
 * com.xgen.atlas.config.provider.localdev.LocalDevBootstrapper}) not reachable via any public CLI flag.
 * Third-party research into how {@code mongodb-atlas-local} itself is composed confirmed the actual
 * supported shape is mongod and mongot as independent processes/containers addressing each other by
 * hostname, which is exactly what running the official image unmodified, as its own container, already
 * gives us for free -- so that's what {@link #ensureRunning()} now also does for Mongo, matched by its own
 * fixed container name ({@value #MONGO_CONTAINER_NAME}) using the same reuse-by-name idempotency as the
 * monolith above, just with no {@code docker build} step of our own since the image is used as-is.
 */
public final class MonolithicContainer {

    private static final String CONTAINER_NAME = "javai-e2e-monolithic";
    private static final String IMAGE_NAME = "javai-e2e-monolithic:latest";
    private static final Path DOCKERFILE = Path.of("docker", "Dockerfile");
    private static final Path BUILD_CONTEXT = Path.of("docker");

    // The MongoDB companion container -- see this class's own javadoc for why it's separate rather than
    // folded into the monolith above. Unmodified official image, no Dockerfile/build step of our own.
    private static final String MONGO_CONTAINER_NAME = "javai-e2e-mongo";
    private static final String MONGO_IMAGE_NAME = "mongodb/mongodb-atlas-local:8.2";

    // Shifted well off each service's common native-install default (5432/7474/7687/11434/27017) so this
    // container's fixed host ports don't collide with, e.g., a locally-installed Ollama, Postgres, or Mongo.
    private static final int HOST_POSTGRES_PORT = 15432;
    private static final int HOST_NEO4J_HTTP_PORT = 17474;
    private static final int HOST_NEO4J_BOLT_PORT = 17687;
    private static final int HOST_OLLAMA_PORT = 21434;
    private static final int HOST_MONGO_PORT = 27417;

    private static final int CONTAINER_POSTGRES_PORT = 5432;
    private static final int CONTAINER_NEO4J_HTTP_PORT = 7474;
    private static final int CONTAINER_NEO4J_BOLT_PORT = 7687;
    private static final int CONTAINER_OLLAMA_PORT = 11434;
    private static final int CONTAINER_MONGO_PORT = 27017;

    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(1);
    // mongodb-atlas-local is a multi-hundred-MB image; the first `docker run` on a machine that doesn't
    // have it cached yet has to pull it, which the plain 1-minute RUN_TIMEOUT isn't sized for.
    private static final Duration MONGO_RUN_TIMEOUT = Duration.ofMinutes(15);

    // javai/javai (Postgres), neo4j/javai12345 (Neo4j) -- baked into docker/Dockerfile itself
    // (CREATE USER .../neo4j-admin dbms set-initial-password ...); centralized here since three separate
    // call sites used to hardcode these identically.
    public static final String POSTGRES_USERNAME = "javai";
    public static final String POSTGRES_PASSWORD = "javai";
    public static final String NEO4J_USERNAME = "neo4j";
    public static final String NEO4J_PASSWORD = "javai12345";

    private static boolean ensured = false;

    private MonolithicContainer() {
    }

    /** Idempotent: safe to call repeatedly, from multiple test classes, across separate JVM runs. */
    public static synchronized void ensureRunning() {
        if (ensured) {
            return;
        }
        // This image bakes in Ollama specifically (see docker/Dockerfile), so the embedding provider
        // selection must be pinned regardless of what LocalEmbeddingDefaults' own host-OS auto-detection
        // would otherwise pick (TEI on Linux/Windows) -- must be set before anything else here runs.
        System.setProperty(LocalEmbeddingDefaults.OVERRIDE_PROPERTY, "ollama");
        if (!isRunning(CONTAINER_NAME)) {
            if (containerExists(CONTAINER_NAME)) {
                run(RUN_TIMEOUT, "docker", "start", CONTAINER_NAME);
            } else {
                if (!imageExists(IMAGE_NAME)) {
                    run(BUILD_TIMEOUT, "docker", "build", "-t", IMAGE_NAME, "-f", DOCKERFILE.toString(), BUILD_CONTEXT.toString());
                }
                run(RUN_TIMEOUT, "docker", "run", "-d", "--name", CONTAINER_NAME,
                        "-p", HOST_POSTGRES_PORT + ":" + CONTAINER_POSTGRES_PORT,
                        "-p", HOST_NEO4J_HTTP_PORT + ":" + CONTAINER_NEO4J_HTTP_PORT,
                        "-p", HOST_NEO4J_BOLT_PORT + ":" + CONTAINER_NEO4J_BOLT_PORT,
                        "-p", HOST_OLLAMA_PORT + ":" + CONTAINER_OLLAMA_PORT,
                        IMAGE_NAME);
            }
        }
        waitForPort(HOST_POSTGRES_PORT);
        waitForPort(HOST_NEO4J_HTTP_PORT);
        waitForPort(HOST_NEO4J_BOLT_PORT);
        waitForPort(HOST_OLLAMA_PORT);
        ensureMongoRunning();
        ensured = true;
    }

    /**
     * Starts the unmodified {@code mongodb/mongodb-atlas-local} companion container if it isn't already
     * running -- see this class's own javadoc for why Mongo lives in its own container rather than as a
     * fourth process inside the monolith. No {@code docker build}: {@code docker run} pulls the image
     * itself the first time, same as any other unmodified base image.
     */
    private static void ensureMongoRunning() {
        if (!isRunning(MONGO_CONTAINER_NAME)) {
            if (containerExists(MONGO_CONTAINER_NAME)) {
                run(RUN_TIMEOUT, "docker", "start", MONGO_CONTAINER_NAME);
            } else {
                run(MONGO_RUN_TIMEOUT, "docker", "run", "-d", "--name", MONGO_CONTAINER_NAME,
                        "-p", HOST_MONGO_PORT + ":" + CONTAINER_MONGO_PORT,
                        MONGO_IMAGE_NAME);
            }
        }
        waitForPort(HOST_MONGO_PORT);
        waitForHealthy(MONGO_CONTAINER_NAME);
    }

    /**
     * Waits for Docker to report {@code container}'s own {@code HEALTHCHECK} as healthy. Required for the
     * MongoDB companion specifically, on top of {@link #waitForPort} (OMI-148): {@code mongodb-atlas-local}
     * restarts {@code mongod} <em>twice</em> while coming up -- once to initialize the replica set, then
     * again with the {@code mongotHost}/{@code searchIndexManagementHostAndPort} parameters set -- and the
     * mapped port already accepts connections during the first of those. A client that starts work at
     * port-open is therefore talking to a deployment about to be torn down under it, and any
     * Search-Index-Management command in flight across a restart comes back as
     * {@code error 90 (CallbackCanceled)}, because {@code mongod}'s shutdown cancels pending search-executor
     * callbacks. The image's own healthcheck only passes after the final restart, which is what makes it the
     * correct readiness signal here -- the same reason {@code RepositoryBackendSpringDataMongoTest} and
     * {@code JavAITaggingMongoE2ETest} use Testcontainers' {@code Wait.forHealthcheck()} rather than its
     * default port-listening strategy. Neither the monolith's own three services nor any other container
     * here declares a {@code HEALTHCHECK}, so this is deliberately called only for the Mongo companion.
     */
    private static void waitForHealthy(String container) {
        Instant deadline = Instant.now().plus(HEALTH_CHECK_TIMEOUT);
        String lastStatus = "unknown";
        while (Instant.now().isBefore(deadline)) {
            lastStatus = runCapturingOutput(RUN_TIMEOUT, "docker", "inspect",
                    "--format", "{{.State.Health.Status}}", container).trim();
            if ("healthy".equals(lastStatus)) {
                return;
            }
            sleep(Duration.ofMillis(500));
        }
        throw new IllegalStateException("Timed out after " + HEALTH_CHECK_TIMEOUT + " waiting for container '"
                + container + "' to report healthy (last status: " + lastStatus + ")");
    }

    public static URI embeddingEndpoint() {
        return URI.create("http://localhost:" + HOST_OLLAMA_PORT);
    }

    /** Same URI as {@link #embeddingEndpoint()} -- one Ollama instance in this container serves both the
     *  embedding model and the chat/completion model (see {@code docker/Dockerfile}). */
    public static URI completionEndpoint() {
        return embeddingEndpoint();
    }

    public static String postgresUrl() {
        return "jdbc:postgresql://localhost:" + HOST_POSTGRES_PORT + "/javai";
    }

    public static String neo4jUri() {
        return "bolt://localhost:" + HOST_NEO4J_BOLT_PORT;
    }

    /** {@code directConnection=true} is required: a single-node replica set (what
     *  {@code mongodb-atlas-local} runs) advertises its own container hostname, which is unreachable from
     *  outside the container -- this bypasses replica-set topology discovery and talks to the mapped port
     *  directly, confirmed the same way {@code RepositoryBackendSpringDataMongoTest} already does. */
    public static String mongoUri() {
        return "mongodb://localhost:" + HOST_MONGO_PORT + "/?directConnection=true";
    }

    private static boolean isRunning(String containerName) {
        return !runCapturingOutput(RUN_TIMEOUT, "docker", "ps",
                "--filter", "name=^/" + containerName + "$", "--filter", "status=running", "-q").isEmpty();
    }

    private static boolean containerExists(String containerName) {
        return !runCapturingOutput(RUN_TIMEOUT, "docker", "ps", "-a",
                "--filter", "name=^/" + containerName + "$", "-q").isEmpty();
    }

    private static boolean imageExists(String imageName) {
        return !runCapturingOutput(RUN_TIMEOUT, "docker", "images", "-q", imageName).isEmpty();
    }

    private static void waitForPort(int port) {
        Instant deadline = Instant.now().plus(HEALTH_CHECK_TIMEOUT);
        IOException lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 1000);
                return;
            } catch (IOException e) {
                lastFailure = e;
                sleep(Duration.ofMillis(500));
            }
        }
        throw new IllegalStateException(
                "Timed out after " + HEALTH_CHECK_TIMEOUT + " waiting for localhost:" + port + " to accept connections",
                lastFailure);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + CONTAINER_NAME, e);
        }
    }

    private static String runCapturingOutput(Duration timeout, String... command) {
        return run(timeout, command);
    }

    /**
     * Reads the process's (merged stdout+stderr) output on a separate thread while this thread blocks on
     * {@link Process#waitFor(long, TimeUnit)} with the given timeout -- deliberately not a plain blocking
     * {@code readLine()} loop before {@code waitFor}, which would defeat the timeout entirely: a hung
     * process that keeps its output stream open blocks {@code readLine()} indefinitely, so the timeout
     * would never even be reached. The reader thread's writes to {@code output} happen-before this thread's
     * read of it once {@link Thread#join} returns, per the Java Memory Model, so no extra synchronization
     * is needed here.
     */
    private static String run(Duration timeout, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(Path.of(System.getProperty("user.dir")).toFile())
                .redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run '" + String.join(" ", command)
                    + "' -- is Docker installed and running?", e);
        }

        StringBuilder output = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException ignored) {
                // stream closed by destroyForcibly() below on a timeout -- nothing more to read
            }
        }, "docker-command-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running '" + String.join(" ", command) + "'", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out after " + timeout + " running '" + String.join(" ", command)
                    + "'. Output so far:\n" + output);
        }
        joinQuietly(outputReader);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Command '" + String.join(" ", command)
                    + "' failed (exit " + process.exitValue() + "). Output:\n" + output);
        }
        return output.toString().strip();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining a docker command's output", e);
        }
    }
}
