package dev.xtrafe.javai.persistence;

import org.hibernate.SessionFactory;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.neo4j.driver.Driver;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Backend selection + connection settings for {@link JavAIPI#repository(Class, JavAIPersistenceConfig)}. Self-contained by
 * default -- {@link #fromSystemProperties()} mirrors {@code JavAIRuntime.configureEmbeddingProvider}'s own
 * {@code javai.embedding.*} pattern -- but fully overridable: {@link Builder#sessionFactory(SessionFactory)}
 * / {@link Builder#neo4jDriver(Driver)} / {@link Builder#mongoTemplate(MongoTemplate)} accept a
 * {@code SessionFactory}/{@code Driver}/{@code MongoTemplate} the calling application already built and
 * owns, instead of letting this module bootstrap its own.
 *
 * <p>System properties read by {@link #fromSystemProperties()}: {@code javai.persistence.backend}
 * ({@code postgres} [default], {@code neo4j}, or {@code mongodb}),
 * {@code javai.persistence.postgres.url|username|password},
 * {@code javai.persistence.neo4j.uri|username|password}, {@code javai.persistence.mongodb.uri|database}.
 */
public final class JavAIPersistenceConfig {

    public enum Backend {
        POSTGRES,
        NEO4J,
        MONGODB
    }

    private final Backend backend;
    private final String postgresUrl;
    private final String postgresUsername;
    private final String postgresPassword;
    private final SessionFactory externalSessionFactory;
    private final PhysicalNamingStrategy physicalNamingStrategy;
    private final Map<String, Object> hibernateProperties;
    private final String neo4jUri;
    private final String neo4jUsername;
    private final String neo4jPassword;
    private final Driver externalNeo4jDriver;
    private final String mongoUri;
    private final String mongoDatabase;
    private final MongoTemplate externalMongoTemplate;

    private JavAIPersistenceConfig(Builder builder) {
        this.backend = builder.backend;
        this.postgresUrl = builder.postgresUrl;
        this.postgresUsername = builder.postgresUsername;
        this.postgresPassword = builder.postgresPassword;
        this.externalSessionFactory = builder.externalSessionFactory;
        this.physicalNamingStrategy = builder.physicalNamingStrategy;
        this.hibernateProperties = Collections.unmodifiableMap(new LinkedHashMap<>(builder.hibernateProperties));
        this.neo4jUri = builder.neo4jUri;
        this.neo4jUsername = builder.neo4jUsername;
        this.neo4jPassword = builder.neo4jPassword;
        this.externalNeo4jDriver = builder.externalNeo4jDriver;
        this.mongoUri = builder.mongoUri;
        this.mongoDatabase = builder.mongoDatabase;
        this.externalMongoTemplate = builder.externalMongoTemplate;
    }

    public static JavAIPersistenceConfig fromSystemProperties() {
        String backendProperty = System.getProperty("javai.persistence.backend", "postgres").toLowerCase(Locale.ROOT);
        Backend backend = switch (backendProperty) {
            case "postgres", "postgres+pgvector" -> Backend.POSTGRES;
            case "neo4j" -> Backend.NEO4J;
            case "mongodb", "mongo" -> Backend.MONGODB;
            default -> throw new IllegalArgumentException("Unknown javai.persistence.backend '" + backendProperty
                    + "' -- expected 'postgres', 'neo4j', or 'mongodb'");
        };
        return builder()
                .backend(backend)
                .postgresUrl(System.getProperty("javai.persistence.postgres.url", "jdbc:postgresql://localhost:5432/javai"))
                .postgresUsername(System.getProperty("javai.persistence.postgres.username", "javai"))
                .postgresPassword(System.getProperty("javai.persistence.postgres.password", "javai"))
                .neo4jUri(System.getProperty("javai.persistence.neo4j.uri", "bolt://localhost:7687"))
                .neo4jUsername(System.getProperty("javai.persistence.neo4j.username", "neo4j"))
                .neo4jPassword(System.getProperty("javai.persistence.neo4j.password", "javai12345"))
                .mongoUri(System.getProperty("javai.persistence.mongodb.uri", "mongodb://localhost:27017"))
                .mongoDatabase(System.getProperty("javai.persistence.mongodb.database", "javai"))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // The plain connection-detail accessors below are public (not just package-private, unlike the
    // external-override accessors further down) specifically so javai-tagging's own backends can build
    // their own independent connections from the exact same values a caller already configured for
    // JavAIPI -- reusing this same config type rather than inventing a parallel one, per doc/spec/tagging.md's
    // "Persistence, across all three backends" (Tags/TagSets/Taggings are ordinary persisted data, not a
    // fourth abstraction). Deliberately does NOT expose a way to reuse the *same* SessionFactory/Driver/
    // MongoTemplate object instance across both modules -- see externalSessionFactory()/externalNeo4jDriver()/
    // externalMongoTemplate() below, which stay package-private; javai-tagging opens its own connections
    // instead, an accepted Phase 0 simplification.
    public Backend backend() {
        return backend;
    }

    public String postgresUrl() {
        return postgresUrl;
    }

    public String postgresUsername() {
        return postgresUsername;
    }

    public String postgresPassword() {
        return postgresPassword;
    }

    SessionFactory externalSessionFactory() {
        return externalSessionFactory;
    }

    /** The explicit physical naming strategy, or {@code null} to use the default -- see
     *  {@link Builder#physicalNamingStrategy(PhysicalNamingStrategy)}. Postgres/Hibernate only. */
    public PhysicalNamingStrategy physicalNamingStrategy() {
        return physicalNamingStrategy;
    }

    /** Extra Hibernate settings applied on top of the ones this module sets itself, in declaration order --
     *  see {@link Builder#hibernateProperty(String, Object)}. Never {@code null}; empty by default. */
    public Map<String, Object> hibernateProperties() {
        return hibernateProperties;
    }

    public String neo4jUri() {
        return neo4jUri;
    }

    public String neo4jUsername() {
        return neo4jUsername;
    }

    public String neo4jPassword() {
        return neo4jPassword;
    }

    Driver externalNeo4jDriver() {
        return externalNeo4jDriver;
    }

    public String mongoUri() {
        return mongoUri;
    }

    public String mongoDatabase() {
        return mongoDatabase;
    }

    MongoTemplate externalMongoTemplate() {
        return externalMongoTemplate;
    }

    public static final class Builder {
        private Backend backend = Backend.POSTGRES;
        private String postgresUrl;
        private String postgresUsername;
        private String postgresPassword;
        private SessionFactory externalSessionFactory;
        private PhysicalNamingStrategy physicalNamingStrategy;
        private final Map<String, Object> hibernateProperties = new LinkedHashMap<>();
        private String neo4jUri;
        private String neo4jUsername;
        private String neo4jPassword;
        private Driver externalNeo4jDriver;
        private String mongoUri;
        private String mongoDatabase;
        private MongoTemplate externalMongoTemplate;

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

        /**
         * Overrides the physical naming strategy applied to the {@code SessionFactory} this module builds.
         * Postgres/Hibernate only -- the Neo4j and MongoDB backends classify by declared field type and have
         * no equivalent of JPA column naming, so this is inert for them.
         *
         * <p>The default is {@code CamelCaseToUnderscoresNamingStrategy}: {@code emailVerified} becomes the
         * column {@code email_verified}, matching Spring Boot's own default and ordinary SQL convention. Pass
         * {@code new PhysicalNamingStrategyStandardImpl()} to pin Hibernate's bare default instead
         * ({@code emailverified}) -- which is what releases before 0.1.5 produced, so this is the supported
         * way for an existing schema to keep its current column names rather than migrate.
         *
         * <p>Takes precedence over a {@code hibernate.physical_naming_strategy} value passed to
         * {@link #hibernateProperty(String, Object)}.
         */
        public Builder physicalNamingStrategy(PhysicalNamingStrategy physicalNamingStrategy) {
            this.physicalNamingStrategy = physicalNamingStrategy;
            return this;
        }

        /**
         * Applies one arbitrary Hibernate setting to the {@code SessionFactory} this module builds -- the
         * general escape hatch for anything this builder doesn't expose a typed method for. Applied
         * <em>after</em> the settings this module sets itself ({@code jakarta.persistence.jdbc.url}/
         * {@code .user}/{@code .password} and {@code hibernate.hbm2ddl.auto}), so a key that collides with
         * one of those wins -- deliberate, since the caller naming a setting explicitly is the more specific
         * instruction. Postgres/Hibernate only, and inert when
         * {@link #sessionFactory(SessionFactory)} supplies a factory this module didn't build.
         */
        public Builder hibernateProperty(String key, Object value) {
            this.hibernateProperties.put(key, value);
            return this;
        }

        /** Bulk form of {@link #hibernateProperty(String, Object)}, applied in the map's own iteration order. */
        public Builder hibernateProperties(Map<String, ?> properties) {
            this.hibernateProperties.putAll(properties);
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

        public Builder mongoUri(String mongoUri) {
            this.mongoUri = mongoUri;
            return this;
        }

        public Builder mongoDatabase(String mongoDatabase) {
            this.mongoDatabase = mongoDatabase;
            return this;
        }

        /** Supplies a {@code MongoTemplate} the calling app already built -- skips self-contained bootstrap. */
        public Builder mongoTemplate(MongoTemplate mongoTemplate) {
            this.externalMongoTemplate = mongoTemplate;
            return this;
        }

        public JavAIPersistenceConfig build() {
            return new JavAIPersistenceConfig(this);
        }
    }
}
