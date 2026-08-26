package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIPI;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.persistence.ModelIds;
import dev.xtrafe.javai.persistence.TaggregateContainment;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String TAG_TEXT_VECTOR_TABLE_PREFIX = "javai_tag_text_vectors__";
    private static final String TAGGREGATE_PENDING_TABLE = "javai_taggregate_pending";

    private final JavAIPersistenceConfig config;
    private final Object lock = new Object();
    private Connection connection;

    /** See {@link #ambient()} -- resolved on first write, never in the constructor. */
    private volatile TaggregateContainment ambient;
    private volatile boolean schemaEnsuredForAmbient;

    TaggingBackendHibernatePostgres(JavAIPersistenceConfig config) {
        this.config = config;
    }

    /**
     * The entity mapper's view of the ambient transaction, resolved on first write (OMI-304).
     *
     * <p>⚠️ <b>Lazily, and that is the whole point.</b> This class deliberately holds a raw
     * {@link Connection} and not a {@code SessionFactory} so that tagging has no startup ordering
     * dependency on the entity mapper -- see the class javadoc. Resolving here rather than in the
     * constructor keeps that true: by the time anything is tagged, the mapper is necessarily up.
     */
    private TaggregateContainment ambient() {
        TaggregateContainment resolved = ambient;
        if (resolved == null) {
            synchronized (lock) {
                resolved = ambient;
                if (resolved == null) {
                    resolved = JavAIPI.taggregateContainment(config);
                    ambient = resolved;
                }
            }
        }
        return resolved;
    }

    /**
     * Runs {@code action} on the caller's own transaction when there is one, so a tag row and the pending
     * row it enqueues commit or roll back together (OMI-304); otherwise on this backend's own connection.
     *
     * <p>The schema is provisioned on this backend's own connection first, once. Doing it on the ambient
     * connection instead would run DDL inside the caller's transaction -- taking locks their commit then
     * has to hold -- and would leave the tables uncreated entirely if that transaction rolled back.
     *
     * @return {@code true} when the work ran on the caller's transaction
     */
    private boolean runOnAmbient(SqlAction action) throws SQLException {
        if (!schemaEnsuredForAmbient) {
            synchronized (lock) {
                connection();   // opens this backend's own connection purely to run ensureSchema once
                schemaEnsuredForAmbient = true;
            }
        }
        return ambient().inAmbientTransaction(action::run);
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
    public List<RankedTaggableRef> nearestByTagSummaryVector(EmbeddingVector reference, int n,
            List<String> candidateTypeNames) {
        return call(connection -> {
            List<RankedTaggableRef> ranked = new ArrayList<>();
            String table = ensureTagSummaryVectorTable(connection, reference.modelId(), reference.dims());
            // The type restriction is an ordinary WHERE, so the planner applies it before ORDER BY/LIMIT --
            // "the nearest n of these types", never "those of the nearest n that are of these types"
            // (OMI-460).
            String sql = "SELECT owner_type, owner_id, (vector <=> ?::vector) AS distance FROM " + table
                    + ownerTypeClause(candidateTypeNames) + " ORDER BY distance LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, toVectorLiteral(reference.values()));
                int index = bindOwnerTypes(statement, 2, candidateTypeNames);
                statement.setInt(index, n);
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

    /** {@code  WHERE owner_type IN (?, ?, …)}, or nothing at all when unnarrowed -- shared by both vector
     *  indexes and both of their counts, so the four cannot narrow differently (OMI-460). */
    private static String ownerTypeClause(List<String> candidateTypeNames) {
        if (candidateTypeNames.isEmpty()) {
            return "";
        }
        return " WHERE owner_type IN ("
                + String.join(",", candidateTypeNames.stream().map(ignored -> "?").toList()) + ")";
    }

    /** Binds {@link #ownerTypeClause}'s placeholders from {@code firstIndex}, returning the next free one. */
    private static int bindOwnerTypes(PreparedStatement statement, int firstIndex, List<String> candidateTypeNames)
            throws SQLException {
        int index = firstIndex;
        for (String typeName : candidateTypeNames) {
            statement.setString(index++, typeName);
        }
        return index;
    }

    @Override
    public int tagSummaryVectorCount(List<String> candidateTypeNames) {
        return call(connection -> countAcross(connection, findAllTagSummaryVectorTables(connection),
                candidateTypeNames));
    }

    /** Distinct owners across every per-model realization of one index, narrowed to {@code candidateTypeNames}
     *  when there are any -- the shape both {@code tagSummaryVectorCount} and {@code tagTextVectorCount}
     *  need, written once. */
    private static int countAcross(Connection connection, List<String> tables, List<String> candidateTypeNames)
            throws SQLException {
        if (tables.isEmpty()) {
            return 0;
        }
        String union = String.join(" UNION ", tables.stream()
                .map(table -> "SELECT owner_type, owner_id FROM " + table).toList());
        String sql = "SELECT count(*) FROM (" + union + ") x" + ownerTypeClause(candidateTypeNames);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindOwnerTypes(statement, 1, candidateTypeNames);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    @Override
    public Map<TaggableRef, List<TagAssociation>> associationsOfAll(Collection<TaggableRef> refs) {
        Map<TaggableRef, List<TagAssociation>> result = new HashMap<>();
        for (TaggableRef ref : refs) {
            result.put(ref, new ArrayList<>());
        }
        if (refs.isEmpty()) {
            return result;
        }
        return call(connection -> {
            // One query for the whole member set -- the recompute cost discipline. Postgres supports a
            // composite-row IN list directly.
            String rowPlaceholders = String.join(",", refs.stream().map(ignored -> "(?,?)").toList());
            String sql = "SELECT taggable_type, taggable_id, tag_id, affinity, source FROM taggings "
                    + "WHERE (taggable_type, taggable_id) IN (" + rowPlaceholders + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (TaggableRef ref : refs) {
                    statement.setString(index++, ref.taggableType());
                    statement.setObject(index++, ref.taggableId());
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        TaggableRef ref = new TaggableRef(
                                resultSet.getString("taggable_type"), (UUID) resultSet.getObject("taggable_id"));
                        double affinity = resultSet.getDouble("affinity");
                        Double affinityOrNull = resultSet.wasNull() ? null : affinity;
                        result.get(ref).add(new TagAssociation(
                                (UUID) resultSet.getObject("tag_id"), affinityOrNull, resultSet.getString("source")));
                    }
                }
            }
            return result;
        });
    }




    @Override
    public void enqueueTaggregatePending(TaggableRef aggregate) {
        run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + TAGGREGATE_PENDING_TABLE
                            + " (id, aggregate_type, aggregate_id, enqueued_at) VALUES (?, ?, ?, ?)")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, aggregate.taggableType());
                statement.setObject(3, aggregate.taggableId());
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public TaggregatePendingClaim claimTaggregatePending(int limit) {
        return call(connection -> {
            Set<TaggableRef> aggregates = new LinkedHashSet<>();
            List<UUID> rowIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, aggregate_type, aggregate_id FROM " + TAGGREGATE_PENDING_TABLE
                            + " ORDER BY enqueued_at LIMIT ?")) {
                statement.setInt(1, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rowIds.add((UUID) resultSet.getObject("id"));
                        aggregates.add(new TaggableRef(
                                resultSet.getString("aggregate_type"), (UUID) resultSet.getObject("aggregate_id")));
                    }
                }
            }
            return new TaggregatePendingClaim(List.copyOf(aggregates), rowIds);
        });
    }

    @Override
    public TaggregatePendingClaim claimTaggregatePendingFor(TaggableRef aggregate) {
        return call(connection -> {
            List<UUID> rowIds = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM " + TAGGREGATE_PENDING_TABLE + " WHERE aggregate_type = ? AND aggregate_id = ?")) {
                statement.setString(1, aggregate.taggableType());
                statement.setObject(2, aggregate.taggableId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rowIds.add((UUID) resultSet.getObject(1));
                    }
                }
            }
            return rowIds.isEmpty() ? TaggregatePendingClaim.EMPTY
                    : new TaggregatePendingClaim(List.of(aggregate), rowIds);
        });
    }

    @Override
    public void deleteTaggregatePending(List<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return;
        }
        run(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + TAGGREGATE_PENDING_TABLE + " WHERE id = ANY (?)")) {
                statement.setArray(1, connection.createArrayOf("uuid", rowIds.toArray()));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public List<RankedTaggableRef> rankedByTags(List<UUID> tagIds, List<String> candidateTypeNames, int limit) {
        if (tagIds.isEmpty() || candidateTypeNames.isEmpty()) {
            return List.of();
        }
        return call(connection -> {
            List<RankedTaggableRef> ranked = new ArrayList<>();
            String tagPlaceholders = String.join(",", tagIds.stream().map(ignored -> "?").toList());
            String typePlaceholders = String.join(",", candidateTypeNames.stream().map(ignored -> "?").toList());
            String sql = "SELECT taggable_type, taggable_id, SUM(COALESCE(affinity, 1.0)) AS score FROM taggings "
                    + "WHERE tag_id IN (" + tagPlaceholders + ") AND taggable_type IN (" + typePlaceholders + ") "
                    + "GROUP BY taggable_type, taggable_id "
                    + "ORDER BY score DESC, taggable_type, taggable_id LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (UUID tagId : tagIds) {
                    statement.setObject(index++, tagId);
                }
                for (String typeName : candidateTypeNames) {
                    statement.setString(index++, typeName);
                }
                statement.setInt(index, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        ranked.add(new RankedTaggableRef(
                                new TaggableRef(resultSet.getString("taggable_type"),
                                        (UUID) resultSet.getObject("taggable_id")),
                                resultSet.getDouble("score")));
                    }
                }
            }
            return ranked;
        });
    }

    @Override
    public void upsertTagTextVector(TaggableRef ref, String text, EmbeddingVector vector) {
        run(connection -> {
            String table = ensureTagTextVectorTable(connection, vector.modelId(), vector.dims());
            String sql = "INSERT INTO " + table + " (owner_type, owner_id, model_id, dims, tag_text, vector, computed_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?::vector, ?) "
                    + "ON CONFLICT (owner_type, owner_id) "
                    + "DO UPDATE SET dims = EXCLUDED.dims, tag_text = EXCLUDED.tag_text, vector = EXCLUDED.vector, "
                    + "computed_at = EXCLUDED.computed_at";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ref.taggableType());
                statement.setObject(2, ref.taggableId());
                statement.setString(3, vector.modelId());
                statement.setInt(4, vector.dims());
                statement.setString(5, text);
                statement.setString(6, toVectorLiteral(vector.values()));
                statement.setTimestamp(7, Timestamp.from(vector.computedAt()));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public void deleteTagTextVector(TaggableRef ref) {
        run(connection -> {
            for (String table : findAllVectorTables(connection, TAG_TEXT_VECTOR_TABLE_PREFIX)) {
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
    public String tagText(TaggableRef ref, String modelId) {
        return call(connection -> {
            String table = TAG_TEXT_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
            if (!findAllVectorTables(connection, TAG_TEXT_VECTOR_TABLE_PREFIX).contains(table)) {
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT tag_text FROM " + table + " WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, ref.taggableType());
                statement.setObject(2, ref.taggableId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getString(1) : null;
                }
            }
        });
    }

    @Override
    public EmbeddingVector tagTextVector(TaggableRef ref, String modelId) {
        return call(connection -> {
            String table = TAG_TEXT_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
            if (!findAllVectorTables(connection, TAG_TEXT_VECTOR_TABLE_PREFIX).contains(table)) {
                return EmbeddingVector.absent();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT model_id, vector::text AS vector, computed_at FROM " + table
                            + " WHERE owner_type = ? AND owner_id = ?")) {
                statement.setString(1, ref.taggableType());
                statement.setObject(2, ref.taggableId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return EmbeddingVector.absent();
                    }
                    float[] values = fromVectorLiteral(resultSet.getString("vector"));
                    return new EmbeddingVector(values, resultSet.getString("model_id"), values.length,
                            resultSet.getTimestamp("computed_at").toInstant());
                }
            }
        });
    }

    @Override
    public List<RankedTaggableRef> nearestByTagTextVector(EmbeddingVector reference, int n,
            List<String> candidateTypeNames) {
        return call(connection -> {
            List<RankedTaggableRef> ranked = new ArrayList<>();
            String table = ensureTagTextVectorTable(connection, reference.modelId(), reference.dims());
            String sql = "SELECT owner_type, owner_id, (vector <=> ?::vector) AS distance FROM " + table
                    + ownerTypeClause(candidateTypeNames) + " ORDER BY distance LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, toVectorLiteral(reference.values()));
                int index = bindOwnerTypes(statement, 2, candidateTypeNames);
                statement.setInt(index, n);
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
    public int tagTextVectorCount(List<String> candidateTypeNames) {
        return call(connection -> countAcross(connection,
                findAllVectorTables(connection, TAG_TEXT_VECTOR_TABLE_PREFIX), candidateTypeNames));
    }

    private static List<String> findAllTagSummaryVectorTables(Connection connection) throws SQLException {
        return findAllVectorTables(connection, TAG_SUMMARY_VECTOR_TABLE_PREFIX);
    }

    private static List<String> findAllVectorTables(Connection connection, String prefix) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND table_name LIKE ? ESCAPE '\\'")) {
            statement.setString(1, prefix.replace("_", "\\_") + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString(1));
                }
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

    /** Same shape as {@link #ensureTagSummaryVectorTable} plus the {@code tag_text} column -- the text is
     *  stored beside the vector it was embedded into, per doc/spec/tagging.md's "Concatenated tag text". */
    private static String ensureTagTextVectorTable(Connection connection, String modelId, int dims) throws SQLException {
        String table = TAG_TEXT_VECTOR_TABLE_PREFIX + ModelIds.sanitize(modelId);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "owner_type   varchar(512) NOT NULL,"
                    + "owner_id     uuid         NOT NULL,"
                    + "model_id     varchar(128) NOT NULL,"
                    + "dims         integer      NOT NULL,"
                    + "tag_text     text         NOT NULL,"
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

    /** Inverse of {@link #toVectorLiteral} -- pgvector's own {@code "[0.1,0.2,0.3]"} text output. */
    private static float[] fromVectorLiteral(String literal) {
        String body = literal.substring(1, literal.length() - 1).trim();
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return values;
    }

    /**
     * Every write this backend makes: on the caller's own transaction when there is one, otherwise on this
     * backend's own connection.
     *
     * <p>Joining the caller's transaction (OMI-304) is what makes a tag mutation and the pending row it
     * enqueues atomic together. On its own autocommit connection the tag row survived a caller's rollback,
     * and the aggregate derived from it was then correct about a tagging the caller believed it had undone.
     */
    private void run(SqlAction action) {
        try {
            if (runOnAmbient(action)) {
                return;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Tagging operation failed on the caller's transaction", e);
        }
        synchronized (lock) {
            try {
                action.run(connection());
            } catch (SQLException e) {
                throw new IllegalStateException("Tagging operation failed against Postgres", e);
            }
        }
    }

    /** Reads join the caller's transaction too, for the reason every read in {@code javai-persistence}
     *  does: rows the caller has written but not yet committed must be visible to its own subsequent read,
     *  which a separate connection could not see. */
    private <T> T call(SqlFunction<T> action) {
        List<T> result = new ArrayList<>(1);
        try {
            if (runOnAmbient(connection -> result.add(action.apply(connection)))) {
                return result.get(0);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Tagging query failed on the caller's transaction", e);
        }
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
            // The membership snapshot this used to keep is gone (OMI-304): the join tables already are the
            // membership, so a query against them cannot be stale and needs no reconciliation pass to
            // become true. Dropped rather than left behind, because a stale copy of containment that
            // nothing maintains is worse than no copy -- a later reader could not tell it was abandoned.
            statement.execute("DROP TABLE IF EXISTS javai_taggregate_members");
            statement.execute("CREATE TABLE IF NOT EXISTS " + TAGGREGATE_PENDING_TABLE + " ("
                    + "id UUID PRIMARY KEY, "
                    + "aggregate_type VARCHAR(512) NOT NULL, "
                    + "aggregate_id UUID NOT NULL, "
                    + "enqueued_at TIMESTAMPTZ NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + TAGGREGATE_PENDING_TABLE + "_aggregate ON "
                    + TAGGREGATE_PENDING_TABLE + " (aggregate_type, aggregate_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS " + TAGGREGATE_PENDING_TABLE + "_enqueued ON "
                    + TAGGREGATE_PENDING_TABLE + " (enqueued_at)");
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
