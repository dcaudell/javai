package dev.xtrafe.javai.persistence;

import org.hibernate.SessionFactory;
import org.neo4j.driver.Driver;

import java.util.Locale;

/**
 * Backend selection + connection settings for {@link JavAIPI#repository(Class)}. Self-contained by
 * default -- {@link #fromSystemProperties()} mirrors {@code JavAIRuntime.configureEmbeddingProvider}'s own
 * {@code javai.embedding.*} pattern -- but fully overridable: {@link Builder#sessionFactory(SessionFactory)}
 * / {@link Builder#neo4jDriver(Driver)} accept a {@code SessionFactory}/{@code Driver} the calling
 * application already built and owns, instead of letting this module bootstrap its own.
 *
 * <p>System properties read by {@link #fromSystemProperties()}: {@code javai.persistence.backend}
 * ({@code postgres} [default] or {@code neo4j}), {@code javai.persistence.postgres.url|username|password},
 * {@code javai.persistence.neo4j.uri|username|password}.
 */
public final class JavAIPersistenceConfig {

    public enum Backend {
        POSTGRES,
        NEO4J
    }

    private final Backend backend;
    private final String postgresUrl;
    private final String postgresUsername;
    private final String postgresPassword;
    private final SessionFactory externalSessionFactory;
    private final String neo4jUri;
    private final String neo4jUsername;
    private final String neo4jPassword;
    private final Driver externalNeo4jDriver;

    private JavAIPersistenceConfig(Builder builder) {
        this.backend = builder.backend;
        this.postgresUrl = builder.postgresUrl;
        this.postgresUsername = builder.postgresUsername;
        this.postgresPassword = builder.postgresPassword;
        this.externalSessionFactory = builder.externalSessionFactory;
        this.neo4jUri = builder.neo4jUri;
        this.neo4jUsername = builder.neo4jUsername;
        this.neo4jPassword = builder.neo4jPassword;
        this.externalNeo4jDriver = builder.externalNeo4jDriver;
    }

    public static JavAIPersistenceConfig fromSystemProperties() {
        String backendProperty = System.getProperty("javai.persistence.backend", "postgres").toLowerCase(Locale.ROOT);
        Backend backend = switch (backendProperty) {
            case "postgres", "postgres+pgvector" -> Backend.POSTGRES;
            case "neo4j" -> Backend.NEO4J;
            default -> throw new IllegalArgumentException(
                    "Unknown javai.persistence.backend '" + backendProperty + "' -- expected 'postgres' or 'neo4j'");
        };
        return builder()
                .backend(backend)
                .postgresUrl(System.getProperty("javai.persistence.postgres.url", "jdbc:postgresql://localhost:5432/javai"))
                .postgresUsername(System.getProperty("javai.persistence.postgres.username", "javai"))
                .postgresPassword(System.getProperty("javai.persistence.postgres.password", "javai"))
                .neo4jUri(System.getProperty("javai.persistence.neo4j.uri", "bolt://localhost:7687"))
                .neo4jUsername(System.getProperty("javai.persistence.neo4j.username", "neo4j"))
                .neo4jPassword(System.getProperty("javai.persistence.neo4j.password", "javai12345"))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    Backend backend() {
        return backend;
    }

    String postgresUrl() {
        return postgresUrl;
    }

    String postgresUsername() {
        return postgresUsername;
    }

    String postgresPassword() {
        return postgresPassword;
    }

    SessionFactory externalSessionFactory() {
        return externalSessionFactory;
    }

    String neo4jUri() {
        return neo4jUri;
    }

    String neo4jUsername() {
        return neo4jUsername;
    }

    String neo4jPassword() {
        return neo4jPassword;
    }

    Driver externalNeo4jDriver() {
        return externalNeo4jDriver;
    }

    public static final class Builder {
        private Backend backend = Backend.POSTGRES;
        private String postgresUrl;
        private String postgresUsername;
        private String postgresPassword;
        private SessionFactory externalSessionFactory;
        private String neo4jUri;
        private String neo4jUsername;
        private String neo4jPassword;
        private Driver externalNeo4jDriver;

        private Builder() {
        }

        public Builder backend(Backend backend) {
            this.backend = backend;
            return this;
        }

        public Builder postgresUrl(String postgresUrl) {
            this.postgresUrl = postgresUrl;
            return this;
        }

        public Builder postgresUsername(String postgresUsername) {
            this.postgresUsername = postgresUsername;
            return this;
        }

        public Builder postgresPassword(String postgresPassword) {
            this.postgresPassword = postgresPassword;
            return this;
        }

        /** Supplies a {@code SessionFactory} the calling app already built -- skips self-contained bootstrap. */
        public Builder sessionFactory(SessionFactory sessionFactory) {
            this.externalSessionFactory = sessionFactory;
            return this;
        }

        public Builder neo4jUri(String neo4jUri) {
            this.neo4jUri = neo4jUri;
            return this;
        }

        public Builder neo4jUsername(String neo4jUsername) {
            this.neo4jUsername = neo4jUsername;
            return this;
        }

        public Builder neo4jPassword(String neo4jPassword) {
            this.neo4jPassword = neo4jPassword;
            return this;
        }

        /** Supplies a {@code Driver} the calling app already built -- skips self-contained bootstrap. */
        public Builder neo4jDriver(Driver neo4jDriver) {
            this.externalNeo4jDriver = neo4jDriver;
            return this;
        }

        public JavAIPersistenceConfig build() {
            return new JavAIPersistenceConfig(this);
        }
    }
}
