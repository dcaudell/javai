# A single Ollama instance with the chat/completion model baked into the image layer, for
# CortexOllamaRealContainerTest -- this module's one real-backend proof (Cortex is otherwise hermetically
# tested against a fake HTTP server, see this module's README). Mirrors e2e-client-test/docker/Dockerfile's
# own "bake the model in at build time, not pull it at container-start time" pattern, but scoped down to
# just this one service -- javai-completion's own tests don't need Postgres/Neo4j at all.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# zstd is required by ollama's own install.sh for extraction (confirmed empirically -- its absence fails
# the install with "This version requires zstd for extraction"), not just curl/ca-certificates.
RUN apt-get update && apt-get install -y curl ca-certificates zstd \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://ollama.com/install.sh | sh

# Matches LocalCompletionDefaults' default model -- qwen3:8b, deliberately more powerful than the smallest
# workable option (see that class's own javadoc for the reasoning).
ARG JAVAI_COMPLETION_MODEL=qwen3:8b
RUN (/usr/local/bin/ollama serve &) \
    && sleep 5 \
    && /usr/local/bin/ollama pull ${JAVAI_COMPLETION_MODEL} \
    && pkill ollama \
    && sleep 2

EXPOSE 11434

# Confirmed empirically, not assumed (same class of bug as e2e-client-test/docker/supervisord.conf's own
# Neo4j listen-address fix): Ollama's own startup log claims "Listening on 127.0.0.1:11434" by default --
# loopback-only, unreachable through Docker's port mapping from outside the container -- unless OLLAMA_HOST
# is explicitly set to bind 0.0.0.0. Only needed here at container-start time; the model-pull step above
# doesn't need it, since its own temporary `ollama serve` and the `ollama pull` client talking to it are
# both inside the same build-time container already, with no port mapping involved.
ENV OLLAMA_HOST=0.0.0.0:11434

CMD ["/usr/local/bin/ollama", "serve"]
