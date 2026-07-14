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
 */
public final class MonolithicContainer {

    private static final String CONTAINER_NAME = "javai-e2e-monolithic";
    private static final String IMAGE_NAME = "javai-e2e-monolithic:latest";
    private static final Path DOCKERFILE = Path.of("docker", "Dockerfile");
    private static final Path BUILD_CONTEXT = Path.of("docker");

    // Shifted well off each service's common native-install default (5432/7474/7687/11434) so this
    // container's fixed host ports don't collide with, e.g., a locally-installed Ollama or Postgres.
    private static final int HOST_POSTGRES_PORT = 15432;
    private static final int HOST_NEO4J_HTTP_PORT = 17474;
    private static final int HOST_NEO4J_BOLT_PORT = 17687;
    private static final int HOST_OLLAMA_PORT = 21434;

    private static final int CONTAINER_POSTGRES_PORT = 5432;
    private static final int CONTAINER_NEO4J_HTTP_PORT = 7474;
    private static final int CONTAINER_NEO4J_BOLT_PORT = 7687;
    private static final int CONTAINER_OLLAMA_PORT = 11434;

    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(1);

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
        if (!isRunning()) {
            if (containerExists()) {
                run(RUN_TIMEOUT, "docker", "start", CONTAINER_NAME);
            } else {
                if (!imageExists()) {
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
        ensured = true;
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

    private static boolean isRunning() {
        return !runCapturingOutput(RUN_TIMEOUT, "docker", "ps",
                "--filter", "name=^/" + CONTAINER_NAME + "$", "--filter", "status=running", "-q").isEmpty();
    }

    private static boolean containerExists() {
        return !runCapturingOutput(RUN_TIMEOUT, "docker", "ps", "-a",
                "--filter", "name=^/" + CONTAINER_NAME + "$", "-q").isEmpty();
    }

    private static boolean imageExists() {
        return !runCapturingOutput(RUN_TIMEOUT, "docker", "images", "-q", IMAGE_NAME).isEmpty();
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
