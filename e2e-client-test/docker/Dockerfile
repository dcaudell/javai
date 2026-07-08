# A single, monolithic image bundling every platform dependency this e2e project needs -- Postgres +
# pgvector, Neo4j, and an embedding provider (Ollama) -- as three supervised processes in one container,
# rather than three separate Testcontainers. Built and run entirely by Testcontainers
# (org.testcontainers.images.builder.ImageFromDockerfile) as part of `mvn test`; nothing here needs a
# manual `docker build` step or a registry push.
#
# Why one container instead of Testcontainers' usual "one container per service": explicitly requested,
# to keep this project's whole e2e surface (in-memory vectorization, both persistence backends, real
# embeddings) served by a single piece of test infrastructure rather than three independently-lifecycled
# ones. The trade-off, stated plainly: this is a bigger, slower-to-build image than composing three
# battle-tested official images would be, and a process going wrong in here is a bit more work to isolate
# than a crashed container with its own name and logs. Postgres, Neo4j, and Ollama were each verified
# working in isolation before being combined -- see the project history for how a couple of real, non-
# obvious bugs surfaced only once real clients connected (Neo4j's Debian package ships every listen-address
# config line commented out, but its *actual* compiled-in default is loopback-only despite its own startup
# log claiming "0.0.0.0" -- fixed below, not assumed).
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# ---- Postgres 16 + pgvector, via the official PGDG apt repository (prebuilt package, no compiling) ----
RUN apt-get update && apt-get install -y curl ca-certificates gnupg lsb-release zstd supervisor \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    && sh -c 'echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list' \
    && apt-get update \
    && apt-get install -y postgresql-16 postgresql-16-pgvector \
    && rm -rf /var/lib/apt/lists/*

# Debian splits Postgres config (/etc/postgresql/16/main) from data (/var/lib/postgresql/16/main) --
# config_file must be passed explicitly (see supervisord.conf), a raw `postgres -D <datadir>` alone assumes
# the non-Debian, config-lives-in-the-data-dir convention and fails to find its own config file.
RUN echo "listen_addresses = '*'" >> /etc/postgresql/16/main/postgresql.conf \
    && echo "host all all 0.0.0.0/0 md5" >> /etc/postgresql/16/main/pg_hba.conf \
    && echo "host all all ::/0 md5" >> /etc/postgresql/16/main/pg_hba.conf

# javai/javai/javai (user/password/db), matching docker/docker-compose.yml's javai-postgres service.
RUN pg_ctlcluster 16 main start \
    && su postgres -c "psql -c \"CREATE USER javai WITH SUPERUSER PASSWORD 'javai';\"" \
    && su postgres -c "psql -c \"CREATE DATABASE javai OWNER javai;\"" \
    && pg_ctlcluster 16 main stop

# ---- Neo4j 5, via Neo4j's own apt repository ----
RUN curl -fsSL https://debian.neo4j.com/neotechnology.gpg.key -o /usr/share/keyrings/neo4j.asc \
    && echo "deb [signed-by=/usr/share/keyrings/neo4j.asc] https://debian.neo4j.com stable 5" > /etc/apt/sources.list.d/neo4j.list \
    && apt-get update \
    && apt-get install -y neo4j \
    && rm -rf /var/lib/apt/lists/*

RUN neo4j-admin dbms set-initial-password javai12345 \
    # Confirmed empirically, not assumed: Debian's neo4j.conf ships every listen/advertised-address line
    # commented out, and Neo4j's actual compiled-in default despite that is loopback-only -- its own
    # startup log claims "Bolt enabled on 0.0.0.0:7687" regardless, which is simply wrong. Without this
    # line, Postgres and Ollama are reachable from outside the container and Neo4j silently is not.
    && echo "server.default_listen_address=0.0.0.0" >> /etc/neo4j/neo4j.conf

# ---- Ollama, with the embedding model baked into the image layer (not pulled at container-start time) ----
# Matches doc/spec/vector-core.md's reference model (Qwen/Qwen3-Embedding-0.6B) and
# LocalEmbeddingDefaults' Ollama tag for it. Baking the model in means `mvn test` never depends on network
# access at run time, only at image-build time -- consistent with this image itself needing to be built
# before the first test run, same as any other Testcontainers image pull.
RUN curl -fsSL https://ollama.com/install.sh | sh

ARG JAVAI_EMBEDDING_MODEL=qwen3-embedding:0.6b
RUN (/usr/local/bin/ollama serve &) \
    && sleep 5 \
    && /usr/local/bin/ollama pull ${JAVAI_EMBEDDING_MODEL} \
    && pkill ollama \
    && sleep 2

# ---- supervisord: the one foreground process managing all three services' lifetimes ----
COPY supervisord.conf /etc/supervisor/supervisord.conf

EXPOSE 5432 7474 7687 11434

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/supervisord.conf"]
