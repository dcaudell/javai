package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.persistence.ModelIds;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@code TAGGED_WITH} relationship from the tagged node to the {@code Tag} node -- a more natural fit
 * than a join-table row, since Neo4j already models associations as first-class relationships (see
 * doc/spec/tagging.md's "Persistence, across all three backends"). {@code MERGE} on the bare relationship
 * pattern (no discriminating properties in the pattern itself) is what actually enforces "zero or one
 * association per instance": a second {@code addTag} call for the same {@code (ref, tagId)} pair matches
 * the existing relationship and updates its properties rather than creating a parallel one.
 *
 * <p>{@code taggableType}/{@code taggableId} are stored as relationship properties, redundant with the
 * connected node's own identity -- deliberately, so {@link #taggedWith} can filter by
 * {@link TaggableRef#taggableType()} (a fully-qualified name) without needing to reconcile that against
 * Neo4j's own node-label convention (the simple name; see {@link TypeNames}), and so every read here is a
 * single relationship-property projection rather than a join back to the node.
 *
 * <p>The tagged node itself is located by {@code (label, id)} -- the label derived from
 * {@link TaggableRef#taggableType()} via {@link TypeNames#simpleNameOf}, matching
 * {@code RepositoryBackendNeo4j}'s own "entity's simple class name is its node label" Phase 0 assumption.
 * This backend never creates that node; {@code addTag} assumes it already exists (saved through the
 * ordinary {@code JavAIRepository} path first), matching the project's general "tagging doesn't own the
 * tagged object's own persistence" boundary.
 */
final class TaggingBackendNeo4j implements TaggingBackend {

    /** The secondary label every tagged node gets (alongside its own entity label) the first time its
     *  tag-summary vector is written -- lets one native vector index span every {@code @Taggable} type at
     *  once, sidestepping Neo4j's per-label vector-index scoping without needing to track which entity
     *  labels have ever been tagged. Removed (not the property) when a node's last Tagging is removed, which
     *  is enough on its own to exclude it from every model's index -- see {@link #deleteTagSummaryVector}. */
    private static final String TAGGED_LABEL = "JavAITagged";

    private final JavAIPersistenceConfig config;
    private final Set<String> tagSummaryVectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private volatile Driver driver;

    TaggingBackendNeo4j(JavAIPersistenceConfig config) {
        this.config = config;
    }

    @Override
    public void addTag(TaggableRef ref, UUID tagId, Double affinity, String source) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        try (Session session = driver().session()) {
            session.run("MATCH (instance:`" + label + "` {id: $instanceId}), (tag:Tag {id: $tagId}) "
                            + "MERGE (instance)-[r:TAGGED_WITH]->(tag) "
                            + "SET r.affinity = $affinity, r.source = $source, r.createdAt = $createdAt, "
                            + "    r.taggableType = $taggableType, r.taggableId = $taggableId",
                    Values.parameters(
                            "instanceId", ref.taggableId().toString(),
                            "tagId", tagId.toString(),
                            "affinity", affinity,
                            "source", source,
                            "createdAt", Instant.now().toString(),
                            "taggableType", ref.taggableType(),
                            "taggableId", ref.taggableId().toString()))
                    .consume();
        }
    }

    @Override
    public void removeTag(TaggableRef ref, UUID tagId) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        try (Session session = driver().session()) {
            session.run("MATCH (instance:`" + label + "` {id: $instanceId})-[r:TAGGED_WITH]->(tag:Tag {id: $tagId}) "
                            + "DELETE r",
                    Values.parameters("instanceId", ref.taggableId().toString(), "tagId", tagId.toString()))
                    .consume();
        }
    }

    @Override
    public boolean hasTag(TaggableRef ref, UUID tagId) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        try (Session session = driver().session()) {
            Record record = session.run(
                    "MATCH (instance:`" + label + "` {id: $instanceId})-[r:TAGGED_WITH]->(tag:Tag {id: $tagId}) "
                            + "RETURN count(r) AS c",
                    Values.parameters("instanceId", ref.taggableId().toString(), "tagId", tagId.toString()))
                    .single();
            return record.get("c").asLong() > 0;
        }
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
        String label = TypeNames.simpleNameOf(ref.taggableType());
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (instance:`" + label + "` {id: $instanceId})-[r:TAGGED_WITH]->(tag:Tag) "
                            + "RETURN tag.id AS id, r.affinity AS affinity, r.source AS source",
                    Values.parameters("instanceId", ref.taggableId().toString()));
            List<TagAssociation> associations = new ArrayList<>();
            for (Record record : result.list()) {
                Double affinity = record.get("affinity").isNull() ? null : record.get("affinity").asDouble();
                associations.add(new TagAssociation(
                        UUID.fromString(record.get("id").asString()), affinity, record.get("source").asString()));
            }
            return associations;
        }
    }

    @Override
    public List<TaggableRef> taggedWith(UUID tagId, List<String> candidateTypeNames) {
        if (candidateTypeNames.isEmpty()) {
            return List.of();
        }
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (tag:Tag {id: $tagId})<-[r:TAGGED_WITH]-() "
                            + "WHERE r.taggableType IN $candidateTypeNames "
                            + "RETURN r.taggableType AS taggableType, r.taggableId AS taggableId",
                    Values.parameters("tagId", tagId.toString(), "candidateTypeNames", candidateTypeNames));
            List<TaggableRef> refs = new ArrayList<>();
            for (Record record : result.list()) {
                refs.add(new TaggableRef(
                        record.get("taggableType").asString(), UUID.fromString(record.get("taggableId").asString())));
            }
            return refs;
        }
    }

    @Override
    public void upsertTagSummaryVector(TaggableRef ref, EmbeddingVector vector) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        String property = qualify(vector.modelId());
        ensureTagSummaryVectorIndex(property, vector.dims());
        try (Session session = driver().session()) {
            session.run("MATCH (instance:`" + label + "` {id: $instanceId}) "
                            + "SET instance:" + TAGGED_LABEL + ", "
                            + "    instance.taggableType = $taggableType, "
                            + "    instance.taggableId = $taggableId, "
                            + "    instance.`" + property + "` = $vector, "
                            + "    instance.`" + property + "ComputedAt` = $computedAt",
                    Values.parameters(
                            "instanceId", ref.taggableId().toString(),
                            "taggableType", ref.taggableType(),
                            "taggableId", ref.taggableId().toString(),
                            "vector", toDoubleList(vector.values()),
                            "computedAt", vector.computedAt().toString()))
                    .consume();
        }
    }

    @Override
    public void deleteTagSummaryVector(TaggableRef ref) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        try (Session session = driver().session()) {
            // Removing the shared label alone is enough to exclude this node from every model's own
            // tag-summary vector index (each scoped to `TAGGED_LABEL`) -- see this backend's own class
            // javadoc for why the model-qualified properties themselves are deliberately left orphaned
            // rather than enumerated and removed one by one.
            session.run("MATCH (instance:`" + label + "` {id: $instanceId}) REMOVE instance:" + TAGGED_LABEL,
                    Values.parameters("instanceId", ref.taggableId().toString()))
                    .consume();
        }
    }

    @Override
    public List<RankedTaggableRef> nearestByTagSummaryVector(EmbeddingVector reference, int n) {
        String property = qualify(reference.modelId());
        ensureTagSummaryVectorIndex(property, reference.dims());
        String indexName = tagSummaryVectorIndexName(property);
        try (Session session = driver().session()) {
            var result = session.run(
                    "CALL db.index.vector.queryNodes($indexName, $limit, $reference) YIELD node, score "
                            + "RETURN node.taggableType AS taggableType, node.taggableId AS taggableId, score AS score",
                    Values.parameters("indexName", indexName, "limit", n, "reference", reference.values()));
            List<RankedTaggableRef> ranked = new ArrayList<>();
            for (Record record : result.list()) {
                TaggableRef ref = new TaggableRef(
                        record.get("taggableType").asString(), UUID.fromString(record.get("taggableId").asString()));
                ranked.add(new RankedTaggableRef(ref, record.get("score").asDouble()));
            }
            return ranked;
        }
    }

    @Override
    public int tagSummaryVectorCount() {
        try (Session session = driver().session()) {
            Record record = session.run("MATCH (n:" + TAGGED_LABEL + ") RETURN count(n) AS c").single();
            return (int) record.get("c").asLong();
        }
    }

    private void ensureTagSummaryVectorIndex(String property, int dims) {
        if (!tagSummaryVectorIndexesEnsured.add(property)) {
            return;
        }
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE VECTOR INDEX `" + tagSummaryVectorIndexName(property) + "` IF NOT EXISTS "
                                + "FOR (n:" + TAGGED_LABEL + ") ON n.`" + property + "` "
                                + "OPTIONS {indexConfig: {`vector.dimensions`: $dims, `vector.similarity_function`: 'cosine'}}",
                        Values.parameters("dims", dims));
                return null;
            });
        }
    }

    private static String tagSummaryVectorIndexName(String property) {
        return "javai_" + TAGGED_LABEL.toLowerCase(java.util.Locale.ROOT) + "_" + property;
    }

    private static String qualify(String modelId) {
        return "tagSummaryVector__" + ModelIds.sanitize(modelId);
    }

    private static List<Double> toDoubleList(float[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add((double) value);
        }
        return list;
    }

    private Driver driver() {
        if (driver == null) {
            synchronized (this) {
                if (driver == null) {
                    driver = GraphDatabase.driver(config.neo4jUri(), AuthTokens.basic(config.neo4jUsername(), config.neo4jPassword()));
                }
            }
        }
        return driver;
    }
}
