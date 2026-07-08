package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.runtime.EmbeddingVector;
import dev.xtrafe.javai.runtime.JavAIVectorizable;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaRoot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Postgres/pgvector backend. Owns one lazily-built, shared internal {@link SessionFactory} (unless the
 * caller supplied one via {@link JavAIPersistenceConfig.Builder#sessionFactory}), covering every entity
 * type registered via {@link #registerEntityType} up to the moment the *first* repository method is
 * actually invoked -- Hibernate's boot-time metadata is immutable once the {@code SessionFactory} is built,
 * so entity types registered afterward would silently never be mapped. See {@link JavAIPI}'s javadoc for
 * the "register every repository before using any of them" usage rule this implies.
 *
 * <p><b>One table per model, not one shared table.</b> Vectors live in tables this backend owns and names
 * itself -- {@code javai_vectors__<model>} (per-field, plus the object's own combined vector under the
 * {@link RepositoryBackend#COMBINED_VECTOR_FIELD} sentinel) and {@code javai_summary_vectors__<model>}
 * ({@code summaryVector()}, one row per object) -- where {@code <model>} is {@link ModelIds#sanitize}
 * applied to {@code EmbeddingVector.modelId()}. This is the key design point, and the reason it replaced an
 * earlier single-shared-table design: pgvector's HNSW index requires a *fixed*-dimension column, which
 * ruled out one column serving multiple models with different output widths; but even for two models that
 * happen to share a dimension, comparing their vectors against each other is semantically meaningless, so
 * physical separation is the correct model regardless of dimension. A concrete, useful consequence:
 * swapping the configured provider is *exactly* the same operation whether or not the new model's output
 * dimension differs from the old one -- {@code javai.persistence.*} config points at a different model, and
 * the next {@code save()} creates that model's own, correctly-dimensioned table the first time it's
 * needed, no special-casing. The developer's own {@code @Entity} table is never touched by this backend
 * beyond ordinary Hibernate persistence of the entity itself.
 *
 * <p><b>Reindexing and reverting.</b> {@link JavAIRepository#reindexAll()} is the explicit trigger for
 * "I swapped providers, now go re-embed everything": it re-saves every existing entity, which -- since
 * {@code save()} always writes under whichever provider is *currently* configured -- populates the new
 * model's tables while leaving every older model's tables completely untouched. Reverting to a previously-
 * used provider therefore needs no reindexing at all: {@code findNearestBy*} resolves its table from the
 * reference vector's own {@code modelId()}, so switching the configured provider back is immediately
 * correct against whatever that model's table already holds.
 */
final class HibernatePostgresRepositoryBackend implements RepositoryBackend {

    private static final String FIELD_VECTOR_TABLE_PREFIX = "javai_vectors__";
    private static final String SUMMARY_VECTOR_TABLE_PREFIX = "javai_summary_vectors__";

    private final JavAIPersistenceConfig config;
    private final Set<Class<?>> registeredEntityTypes = ConcurrentHashMap.newKeySet();
    private final Object bootstrapLock = new Object();
    private volatile SessionFactory sessionFactory;

    HibernatePostgresRepositoryBackend(JavAIPersistenceConfig config) {
        this.config = config;
    }

    @Override
    public void registerEntityType(Class<?> entityType) {
        if (sessionFactory != null) {
            throw new IllegalStateException("Cannot register " + entityType.getName() + " -- the SessionFactory "
                    + "was already built from an earlier repository()'s first use. Call JavAIPI.repository(...) "
                    + "for every repository interface before invoking methods on any of them.");
        }
        registeredEntityTypes.add(entityType);
    }

    @Override
    public Object save(Class<?> entityType, Object entity) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                if (EntityReflection.readId(entity) == null) {
                    EntityReflection.writeId(entity, UUID.randomUUID());
                }
                Object managed = session.merge(entity);
                session.flush();
                writeVectors(session, entityType, managed);
                tx.commit();
                return managed;
            } catch (RuntimeException e) {
                tx.rollback();
                throw e;
            }
        }
    }

    @Override
    public Optional<Object> findById(Class<?> entityType, UUID id) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            return Optional.ofNullable(session.find(entityType, id));
        }
    }

    @Override
    public List<Object> findAll(Class<?> entityType) {
        return findAllTyped(entityType);
    }

    @SuppressWarnings("unchecked")
    private <T> List<Object> findAllTyped(Class<T> entityType) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            JpaCriteriaQuery<T> query = session.getCriteriaBuilder().createQuery(entityType);
            JpaRoot<T> root = query.from(entityType);
            query.select(root);
            return (List<Object>) (List<?>) session.createQuery(query).list();
        }
    }

    @Override
    public void deleteById(Class<?> entityType, UUID id) {
        SessionFactory factory = sessionFactory();
        try (Session session = factory.openSession()) {
            Transaction tx = session.beginTransaction();
            try {
                Object entity = session.find(entityType, id);
                if (entity != null) {
                    session.remove(entity);
                }
                deleteVectors(session, entityType.getName(), id);
                tx.commit();
            } catch (RuntimeException e) {
                tx.rollback();
                throw e;
            }
        }
    }

    @Override
    public List<Object> findNearestByFieldVector(
            Class<?> entityType, String fieldName, EmbeddingVector reference, int limit) {
        SessionFactory factory = sessionFactory();
        List<UUID> rankedIds;
        try (Session session = factory.openSession()) {
            rankedIds = session.doReturningWork(connection -> {
                String table = ensureFieldVectorTable(connection, reference.modelId(), reference.dims());
                return rankIds(connection, table, entityType, fieldName, reference, limit);
            });
        }
        return hydrate(factory, entityType, rankedIds);
    }

    @Override
    public List<Object> findNearestBySummaryVector(Class<?> entityType, EmbeddingVector reference, int limit) {
        SessionFactory factory = sessionFactory();
        List<UUID> rankedIds;
        try (Session session = factory.openSession()) {
            rankedIds = session.doReturningWork(connection -> {
                String table = ensureSummaryVectorTable(connection, reference.modelId(), reference.dims());
                return rankIds(connection, table, entityType, null, reference, limit);
            });
        }
        return hydrate(factory, entityType, rankedIds);
    }

    // ---- vector read/write -------------------------------------------------------------------

    private void writeVectors(Session session, Class<?> entityType, Object entity) {
        JavAIVectorizable vectorizable = (JavAIVectorizable) entity;
        UUID id = EntityReflection.readId(entity);
        String ownerType = entityType.getName();

        session.doWork(connection -> {
            for (String fieldName : EntityReflection.vectorizeFieldNames(entityType)) {
                EmbeddingVector vector = vectorizable.fieldVector(fieldName);
                String table = ensureFieldVectorTable(connection, vector.modelId(), vector.dims());
                upsertVector(connection, table, ownerType, id, fieldName, vector);
            }
            EmbeddingVector combined = vectorizable.vector();
            String combinedTable = ensureFieldVectorTable(connection, combined.modelId(), combined.dims());
            upsertVector(connection, combinedTable, ownerType, id, COMBINED_VECTOR_FIELD, combined);

            EmbeddingVector summary = vectorizable.summaryVector();
            String summaryTable = ensureSummaryVectorTable(connection, summary.modelId(), summary.dims());
            upsertSummaryVector(connection, summaryTable, ownerType, id, summary);
        });
    }

    private static void upsertVector(Connection connection, String table, String ownerType, UUID ownerId,
            String fieldName, EmbeddingVector vector) throws SQLException {
        String sql = "INSERT INTO " + table + " (owner_type, owner_id, field_name, model_id, dims, vector, computed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?::vector, ?) "
                + "ON CONFLICT (owner_type, owner_id, field_name) "
                + "DO UPDATE SET dims = EXCLUDED.dims, vector = EXCLUDED.vector, computed_at = EXCLUDED.computed_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, fieldName);
            statement.setString(4, vector.modelId());
            statement.setInt(5, vector.dims());
            statement.setString(6, toVectorLiteral(vector.values()));
            statement.setTimestamp(7, Timestamp.from(vector.computedAt()));
            statement.executeUpdate();
        }
    }

    private static void upsertSummaryVector(Connection connection, String table, String ownerType, UUID ownerId,
            EmbeddingVector vector) throws SQLException {
        String sql = "INSERT INTO " + table + " (owner_type, owner_id, model_id, dims, vector, computed_at) "
                + "VALUES (?, ?, ?, ?, ?::vector, ?) "
                + "ON CONFLICT (owner_type, owner_id) "
                + "DO UPDATE SET dims = EXCLUDED.dims, vector = EXCLUDED.vector, computed_at = EXCLUDED.computed_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerType);
            statement.setObject(2, ownerId);
            statement.setString(3, vector.modelId());
            statement.setInt(4, vector.dims());
            statement.setString(5, toVectorLiteral(vector.values()));
            statement.setTimestamp(6, Timestamp.from(vector.computedAt()));
            statement.executeUpdate();
        }
    }

    /** Deletes {@code (ownerType, id)}'s rows from *every* per-model table that currently exists --
     *  queried from the catalog, not this backend instance's in-memory {@link #knownTables}, since that
     *  only reflects tables created during this process's own lifetime; a table created by an earlier
     *  process run (a model used before the most recent restart) is just as real and just as much this
     *  entity's data to clean up. */
    private static void deleteVectors(Session session, String ownerType, UUID id) {
        session.doWork(connection -> {
            for (String table : findAllVectorTables(connection)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE owner_type = ? AND owner_id = ?")) {
                    statement.setString(1, ownerType);
                    statement.setObject(2, id);
                    statement.executeUpdate();
                }
            }
        });
    }

    private static List<String> findAllVectorTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND (table_name LIKE 'javai\\_vectors\\_\\_%' ESCAPE '\\' "
                        + "OR table_name LIKE 'javai\\_summary\\_vectors\\_\\_%' ESCAPE '\\')");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    /** Rank-then-hydrate's first half: ANN search entirely within one model's own vector table, bounded by
     *  LIMIT, before any of the entity's own table is touched. {@code fieldName} is null for the summary
     *  table, which has no per-field dimension. */
    private static List<UUID> rankIds(Connection connection, String table, Class<?> entityType, String fieldName,
            EmbeddingVector reference, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT owner_id FROM ").append(table)
                .append(" WHERE owner_type = ?");
        if (fieldName != null) {
            sql.append(" AND field_name = ?");
        }
        sql.append(" ORDER BY vector <=> ?::vector LIMIT ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, entityType.getName());
            if (fieldName != null) {
                statement.setString(index++, fieldName);
            }
            statement.setString(index++, toVectorLiteral(reference.values()));
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (resultSet.next()) {
                    ids.add((UUID) resultSet.getObject("owner_id"));
                }
                return ids;
            }
        }
    }

    /** Second half: a targeted load of exactly the ranked ids, one {@code find} per id (bounded by
     *  {@code limit}, typically small) -- simpler and just as correct as a batch multi-load API for this
     *  volume, and trivially preserves rank order without a separate re-sort step. */
    private static List<Object> hydrate(SessionFactory factory, Class<?> entityType, List<UUID> rankedIds) {
        try (Session session = factory.openSession()) {
            List<Object> results = new ArrayList<>(rankedIds.size());
            for (UUID id : rankedIds) {
                Object entity = session.find(entityType, id);
                if (entity != null) {
                    results.add(entity);
                }
            }
            return results;
        }
    }

    /** pgvector's own text input format for a vector literal, e.g. {@code "[0.1,0.2,0.3]"} -- avoids
     *  needing a JDBC-level PGobject type (and the connection-unwrapping uncertainty that comes with one
     *  under a layer like Hibernate) just to bind a vector-shaped query parameter. */
    private static String toVectorLiteral(float[] values) {
        StringBuilder literal = new StringBuilder(values.length * 8).append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(values[i]);
        }
        return literal.append(']').toString();
    }

    /** Creates {@code javai_vectors__<model>} (fixed to {@code dims} from the moment it's created -- known
     *  upfront now, since only one model's vectors will ever land in this specific table) if it doesn't
     *  already exist, and returns its name. Deliberately re-checked on every call rather than cached after
     *  the first success: this DDL runs inside the same transaction as the rest of {@code save()}, and an
     *  earlier in-memory "already created" cache surviving a *later* failure in that same transaction --
     *  which rolls the CREATE TABLE back too, DDL being transactional in Postgres -- left the cache
     *  permanently out of sync with reality, breaking every subsequent save() for the rest of this backend
     *  instance's lifetime. {@code CREATE TABLE IF NOT EXISTS} is already a cheap, idempotent catalog
     *  check; there's no correctness-safe way to skip re-running it. */
    private static String ensureFieldVectorTable(Connection connection, String modelId, int dims) throws SQLException {
        String table = FIELD_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "owner_type   varchar(255) NOT NULL,"
                    + "owner_id     uuid         NOT NULL,"
                    + "field_name   varchar(128) NOT NULL,"
                    + "model_id     varchar(128) NOT NULL,"
                    + "dims         integer      NOT NULL,"
                    + "vector       vector(" + dims + ") NOT NULL,"
                    + "computed_at  timestamptz  NOT NULL,"
                    + "PRIMARY KEY (owner_type, owner_id, field_name))");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_lookup ON " + table + " (owner_type, field_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_hnsw ON " + table + " USING hnsw (vector vector_cosine_ops)");
        }
        return table;
    }

    private static String ensureSummaryVectorTable(Connection connection, String modelId, int dims) throws SQLException {
        String table = SUMMARY_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "owner_type   varchar(255) NOT NULL,"
                    + "owner_id     uuid         NOT NULL,"
                    + "model_id     varchar(128) NOT NULL,"
                    + "dims         integer      NOT NULL,"
                    + "vector       vector(" + dims + ") NOT NULL,"
                    + "computed_at  timestamptz  NOT NULL,"
                    + "PRIMARY KEY (owner_type, owner_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_lookup ON " + table + " (owner_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_hnsw ON " + table + " USING hnsw (vector vector_cosine_ops)");
        }
        return table;
    }

    // ---- lazy bootstrap -----------------------------------------------------------------------

    private SessionFactory sessionFactory() {
        SessionFactory factory = sessionFactory;
        if (factory != null) {
            return factory;
        }
        synchronized (bootstrapLock) {
            if (sessionFactory == null) {
                sessionFactory = config.externalSessionFactory() != null
                        ? config.externalSessionFactory()
                        : buildSessionFactory();
                initializeSchema(sessionFactory);
            }
            return sessionFactory;
        }
    }

    private SessionFactory buildSessionFactory() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("jakarta.persistence.jdbc.url", config.postgresUrl())
                .applySetting("jakarta.persistence.jdbc.user", config.postgresUsername())
                .applySetting("jakarta.persistence.jdbc.password", config.postgresPassword())
                .applySetting("hibernate.hbm2ddl.auto", "update")
                .build();
        MetadataSources sources = new MetadataSources(registry);
        for (Class<?> entityType : registeredEntityTypes) {
            sources.addAnnotatedClass(entityType);
        }
        return sources.buildMetadata().buildSessionFactory();
    }

    /** Only the pgvector extension itself -- every actual vector table is per-model and created lazily
     *  (see {@link #ensureFieldVectorTable}/{@link #ensureSummaryVectorTable}), since which models will
     *  ever be used isn't known at bootstrap time. */
    private static void initializeSchema(SessionFactory factory) {
        try (Session session = factory.openSession()) {
            session.doWork(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
                }
            });
        }
    }
}
