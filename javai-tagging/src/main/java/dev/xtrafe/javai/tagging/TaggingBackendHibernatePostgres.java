package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.persistence.ModelIds;
import dev.xtrafe.javai.vector.EmbeddingVector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Raw JDBC, mirroring {@code javai-persistence}'s own {@code RepositoryBackendHibernatePostgres} escape-
 * hatch style -- a plain {@code taggings} join table, not a Hibernate {@code @Entity} (this backend never
 * touches the {@code SessionFactory} that manages Tag/TagSet's own tables, so there is no ordering
 * dependency between the two at startup). A single JDBC {@link Connection} is lazily opened and reused,
 * serialized by {@link #lock} since a plain {@code Connection} isn't safe for concurrent use the way a
 * Hibernate {@code SessionFactory} or a Neo4j {@code Driver} already is.
 */
final class TaggingBackendHibernatePostgres implements TaggingBackend {

    private static final String TAG_SUMMARY_VECTOR_TABLE_PREFIX = "javai_tag_summary_vectors__";

    private final JavAIPersistenceConfig config;
    private final Object lock = new Object();
    private Connection connection;

    TaggingBackendHibernatePostgres(JavAIPersistenceConfig config) {
        this.config = config;
    }

    @Override
    public void addTag(TaggableRef ref, UUID tagId, Double affinity, String source) {
        run(connection -> {
            String sql = "INSERT INTO taggings (id, tag_id, taggable_type, taggable_id, affinity, source, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (tag_id, taggable_type, taggable_id) "
                    + "DO UPDATE SET affinity = EXCLUDED.affinity, source = EXCLUDED.source";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, tagId);
                statement.setString(3, ref.taggableType());
                statement.setObject(4, ref.taggableId());
                if (affinity == null) {
                    statement.setNull(5, Types.DOUBLE);
                } else {
                    statement.setDouble(5, affinity);
                }
                statement.setString(6, source);
                statement.setTimestamp(7, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void removeTag(TaggableRef ref, UUID tagId) {
        run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM taggings WHERE tag_id = ? AND taggable_type = ? AND taggable_id = ?")) {
                statement.setObject(1, tagId);
                statement.setString(2, ref.taggableType());
                statement.setObject(3, ref.taggableId());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public boolean hasTag(TaggableRef ref, UUID tagId) {
        return call(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM taggings WHERE tag_id = ? AND taggable_type = ? AND taggable_id = ?")) {
                statement.setObject(1, tagId);
                statement.setString(2, ref.taggableType());
                statement.setObject(3, ref.taggableId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        });
    }

    @Override
    public List<UUID> tagIdsOf(TaggableRef ref) {
        List<UUID> ids = new ArrayList<>();
        for (TagAssociation association : associationsOf(ref)) {
            ids.add(association.tagId());
        }
        return ids;
    }

    @Override
    public List<TagAssociation> associationsOf(TaggableRef ref) {
        return call(connection -> {
            List<TagAssociation> associations = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT tag_id, affinity, source FROM taggings WHERE taggable_type = ? AND taggable_id = ?")) {
                statement.setString(1, ref.taggableType());
                statement.setObject(2, ref.taggableId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        double affinity = resultSet.getDouble("affinity");
                        Double affinityOrNull = resultSet.wasNull() ? null : affinity;
                        associations.add(new TagAssociation(
                                (UUID) resultSet.getObject("tag_id"), affinityOrNull, resultSet.getString("source")));
                    }
                }
            }
            return associations;
        });
    }

    @Override
    public List<TaggableRef> taggedWith(UUID tagId, List<String> candidateTypeNames) {
        if (candidateTypeNames.isEmpty()) {
            return List.of();
        }
        return call(connection -> {
            List<TaggableRef> refs = new ArrayList<>();
            String placeholders = String.join(",", candidateTypeNames.stream().map(ignored -> "?").toList());
            String sql = "SELECT taggable_type, taggable_id FROM taggings WHERE tag_id = ? AND taggable_type IN ("
                    + placeholders + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, tagId);
                int index = 2;
                for (String typeName : candidateTypeNames) {
                    statement.setString(index++, typeName);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        refs.add(new TaggableRef(
                                resultSet.getString("taggable_type"), (UUID) resultSet.getObject("taggable_id")));
                    }
                }
            }
            return refs;
        });
    }

    @Override
    public void upsertTagSummaryVector(TaggableRef ref, EmbeddingVector vector) {
        run(connection -> {
            String table = ensureTagSummaryVectorTable(connection, vector.modelId(), vector.dims());
            String sql = "INSERT INTO " + table + " (owner_type, owner_id, model_id, dims, vector, computed_at) "
                    + "VALUES (?, ?, ?, ?, ?::vector, ?) "
                    + "ON CONFLICT (owner_type, owner_id) "
                    + "DO UPDATE SET dims = EXCLUDED.dims, vector = EXCLUDED.vector, computed_at = EXCLUDED.computed_at";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ref.taggableType());
                statement.setObject(2, ref.taggableId());
                statement.setString(3, vector.modelId());
                statement.setInt(4, vector.dims());
                statement.setString(5, toVectorLiteral(vector.values()));
                statement.setTimestamp(6, Timestamp.from(vector.computedAt()));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void deleteTagSummaryVector(TaggableRef ref) {
        run(connection -> {
            for (String table : findAllTagSummaryVectorTables(connection)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE owner_type = ? AND owner_id = ?")) {
                    statement.setString(1, ref.taggableType());
                    statement.setObject(2, ref.taggableId());
                    statement.executeUpdate();
                }
            }
        });
    }

    @Override
    public List<RankedTaggableRef> nearestByTagSummaryVector(EmbeddingVector reference, int n) {
        return call(connection -> {
            List<RankedTaggableRef> ranked = new ArrayList<>();
            String table = ensureTagSummaryVectorTable(connection, reference.modelId(), reference.dims());
            String sql = "SELECT owner_type, owner_id, (vector <=> ?::vector) AS distance FROM " + table
                    + " ORDER BY distance LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, toVectorLiteral(reference.values()));
                statement.setInt(2, n);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        TaggableRef ref = new TaggableRef(
                                resultSet.getString("owner_type"), (UUID) resultSet.getObject("owner_id"));
                        ranked.add(new RankedTaggableRef(ref, 1.0 - resultSet.getDouble("distance")));
                    }
                }
            }
            return ranked;
        });
    }

    @Override
    public int tagSummaryVectorCount() {
        return call(connection -> {
            List<String> tables = findAllTagSummaryVectorTables(connection);
            if (tables.isEmpty()) {
                return 0;
            }
            String union = String.join(" UNION ", tables.stream()
                    .map(table -> "SELECT owner_type, owner_id FROM " + table).toList());
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM (" + union + ") x")) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        });
    }

    private static List<String> findAllTagSummaryVectorTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND table_name LIKE 'javai\\_tag\\_summary\\_vectors\\_\\_%' ESCAPE '\\'");
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    private static String ensureTagSummaryVectorTable(Connection connection, String modelId, int dims) throws SQLException {
        String table = TAG_SUMMARY_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "owner_type   varchar(512) NOT NULL,"
                    + "owner_id     uuid         NOT NULL,"
                    + "model_id     varchar(128) NOT NULL,"
                    + "dims         integer      NOT NULL,"
                    + "vector       vector(" + dims + ") NOT NULL,"
                    + "computed_at  timestamptz  NOT NULL,"
                    + "PRIMARY KEY (owner_type, owner_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_hnsw ON " + table + " USING hnsw (vector vector_cosine_ops)");
        }
        return table;
    }

    /** pgvector's own text input format for a vector literal, e.g. {@code "[0.1,0.2,0.3]"} -- mirrors
     *  {@code RepositoryBackendHibernatePostgres}'s own identical helper. */
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

    private void run(SqlAction action) {
        synchronized (lock) {
            try {
                action.run(connection());
            } catch (SQLException e) {
                throw new IllegalStateException("Tagging operation failed against Postgres", e);
            }
        }
    }

    private <T> T call(SqlFunction<T> action) {
        synchronized (lock) {
            try {
                return action.apply(connection());
            } catch (SQLException e) {
                throw new IllegalStateException("Tagging query failed against Postgres", e);
            }
        }
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(config.postgresUrl(), config.postgresUsername(), config.postgresPassword());
            ensureSchema(connection);
        }
        return connection;
    }

    private void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS taggings ("
                    + "id UUID PRIMARY KEY, "
                    + "tag_id UUID NOT NULL, "
                    + "taggable_type VARCHAR(512) NOT NULL, "
                    + "taggable_id UUID NOT NULL, "
                    + "affinity DOUBLE PRECISION, "
                    + "source VARCHAR(16) NOT NULL, "
                    + "created_at TIMESTAMP NOT NULL, "
                    + "UNIQUE (tag_id, taggable_type, taggable_id))");
            statement.execute("CREATE INDEX IF NOT EXISTS taggings_taggable_lookup ON taggings (taggable_type, taggable_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS taggings_tag_lookup ON taggings (tag_id)");
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
