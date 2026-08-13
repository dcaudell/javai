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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Tag-text sibling of {@link #TAGGED_LABEL} -- its own label so the tag-text vector indexes (scoped
     *  per label) stay independent of the tag-summary ones, and so removing one capability's entries never
     *  disturbs the other's. */
    private static final String TAG_TEXTED_LABEL = "JavAITagTexted";

    /** Membership snapshot and pending set as their own property nodes (`javai_taggregate_members` /
     *  `javai_taggregate_pending` in this backend's convention) -- ref-shaped data, deliberately not
     *  relationships between entity nodes: a snapshot row must survive its member node's deletion, and is
     *  written wholesale by recompute, never navigated as graph structure. Correct-everywhere shape first. */
    private static final String TAGGREGATE_MEMBER_LABEL = "JavAITaggregateMember";
    private static final String TAGGREGATE_PENDING_LABEL = "JavAITaggregatePending";

    private final JavAIPersistenceConfig config;
    private final Set<String> tagSummaryVectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final Set<String> tagTextVectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean taggregateIndexesEnsured = new AtomicBoolean();
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

    @Override
    public Map<TaggableRef, List<TagAssociation>> associationsOfAll(Collection<TaggableRef> refs) {
        Map<TaggableRef, List<TagAssociation>> result = new HashMap<>();
        List<Map<String, Object>> refParameters = new ArrayList<>();
        for (TaggableRef ref : refs) {
            result.put(ref, new ArrayList<>());
            refParameters.add(Map.of("type", ref.taggableType(), "id", ref.taggableId().toString()));
        }
        if (refs.isEmpty()) {
            return result;
        }
        try (Session session = driver().session()) {
            // One query for the whole member set, matching by the relationship's own ref properties (see
            // class javadoc for why those are stored redundantly) -- no per-ref round trips.
            var queryResult = session.run(
                    "UNWIND $refs AS ref "
                            + "MATCH ()-[r:TAGGED_WITH]->(tag:Tag) "
                            + "WHERE r.taggableType = ref.type AND r.taggableId = ref.id "
                            + "RETURN ref.type AS type, ref.id AS id, tag.id AS tagId, "
                            + "r.affinity AS affinity, r.source AS source",
                    Values.parameters("refs", refParameters));
            for (Record record : queryResult.list()) {
                TaggableRef ref = new TaggableRef(
                        record.get("type").asString(), UUID.fromString(record.get("id").asString()));
                Double affinity = record.get("affinity").isNull() ? null : record.get("affinity").asDouble();
                result.get(ref).add(new TagAssociation(
                        UUID.fromString(record.get("tagId").asString()), affinity, record.get("source").asString()));
            }
        }
        return result;
    }

    @Override
    public void replaceTaggregateMembers(TaggableRef aggregate, List<TaggableRef> members) {
        ensureTaggregateIndexes();
        List<Map<String, Object>> memberParameters = new ArrayList<>();
        for (TaggableRef member : members) {
            memberParameters.add(Map.of("type", member.taggableType(), "id", member.taggableId().toString()));
        }
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (m:" + TAGGREGATE_MEMBER_LABEL + " {aggregateType: $aggregateType, aggregateId: $aggregateId}) "
                                + "DELETE m",
                        Values.parameters(
                                "aggregateType", aggregate.taggableType(),
                                "aggregateId", aggregate.taggableId().toString()));
                tx.run("UNWIND $members AS member "
                                + "CREATE (:" + TAGGREGATE_MEMBER_LABEL + " {aggregateType: $aggregateType, "
                                + "aggregateId: $aggregateId, memberType: member.type, memberId: member.id})",
                        Values.parameters(
                                "aggregateType", aggregate.taggableType(),
                                "aggregateId", aggregate.taggableId().toString(),
                                "members", memberParameters));
                return null;
            });
        }
    }

    @Override
    public List<TaggableRef> taggregateMembers(TaggableRef aggregate) {
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (m:" + TAGGREGATE_MEMBER_LABEL + " {aggregateType: $aggregateType, aggregateId: $aggregateId}) "
                            + "RETURN m.memberType AS type, m.memberId AS id",
                    Values.parameters(
                            "aggregateType", aggregate.taggableType(),
                            "aggregateId", aggregate.taggableId().toString()));
            List<TaggableRef> members = new ArrayList<>();
            for (Record record : result.list()) {
                members.add(new TaggableRef(record.get("type").asString(), UUID.fromString(record.get("id").asString())));
            }
            return members;
        }
    }

    @Override
    public List<TaggableRef> taggregatesContaining(TaggableRef member) {
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (m:" + TAGGREGATE_MEMBER_LABEL + " {memberType: $memberType, memberId: $memberId}) "
                            + "RETURN m.aggregateType AS type, m.aggregateId AS id",
                    Values.parameters(
                            "memberType", member.taggableType(),
                            "memberId", member.taggableId().toString()));
            List<TaggableRef> aggregates = new ArrayList<>();
            for (Record record : result.list()) {
                aggregates.add(new TaggableRef(record.get("type").asString(), UUID.fromString(record.get("id").asString())));
            }
            return aggregates;
        }
    }

    @Override
    public void enqueueTaggregatePending(TaggableRef aggregate) {
        ensureTaggregateIndexes();
        try (Session session = driver().session()) {
            session.run("CREATE (:" + TAGGREGATE_PENDING_LABEL + " {id: $id, aggregateType: $aggregateType, "
                            + "aggregateId: $aggregateId, enqueuedAt: $enqueuedAt})",
                    Values.parameters(
                            "id", UUID.randomUUID().toString(),
                            "aggregateType", aggregate.taggableType(),
                            "aggregateId", aggregate.taggableId().toString(),
                            "enqueuedAt", Instant.now().toEpochMilli()))
                    .consume();
        }
    }

    @Override
    public TaggregatePendingClaim claimTaggregatePending(int limit) {
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (n:" + TAGGREGATE_PENDING_LABEL + ") "
                            + "RETURN n.id AS id, n.aggregateType AS type, n.aggregateId AS aggregateId "
                            + "ORDER BY n.enqueuedAt LIMIT $limit",
                    Values.parameters("limit", limit));
            Set<TaggableRef> aggregates = new LinkedHashSet<>();
            List<UUID> rowIds = new ArrayList<>();
            for (Record record : result.list()) {
                rowIds.add(UUID.fromString(record.get("id").asString()));
                aggregates.add(new TaggableRef(
                        record.get("type").asString(), UUID.fromString(record.get("aggregateId").asString())));
            }
            return new TaggregatePendingClaim(List.copyOf(aggregates), rowIds);
        }
    }

    @Override
    public TaggregatePendingClaim claimTaggregatePendingFor(TaggableRef aggregate) {
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (n:" + TAGGREGATE_PENDING_LABEL + " {aggregateType: $aggregateType, aggregateId: $aggregateId}) "
                            + "RETURN n.id AS id",
                    Values.parameters(
                            "aggregateType", aggregate.taggableType(),
                            "aggregateId", aggregate.taggableId().toString()));
            List<UUID> rowIds = new ArrayList<>();
            for (Record record : result.list()) {
                rowIds.add(UUID.fromString(record.get("id").asString()));
            }
            return rowIds.isEmpty() ? TaggregatePendingClaim.EMPTY
                    : new TaggregatePendingClaim(List.of(aggregate), rowIds);
        }
    }

    @Override
    public void deleteTaggregatePending(List<UUID> rowIds) {
        if (rowIds.isEmpty()) {
            return;
        }
        try (Session session = driver().session()) {
            session.run("UNWIND $ids AS pendingId "
                            + "MATCH (n:" + TAGGREGATE_PENDING_LABEL + " {id: pendingId}) DELETE n",
                    Values.parameters("ids", rowIds.stream().map(UUID::toString).toList()))
                    .consume();
        }
    }

    @Override
    public List<RankedTaggableRef> rankedByTags(List<UUID> tagIds, List<String> candidateTypeNames, int limit) {
        if (tagIds.isEmpty() || candidateTypeNames.isEmpty()) {
            return List.of();
        }
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (tag:Tag)<-[r:TAGGED_WITH]-() "
                            + "WHERE tag.id IN $tagIds AND r.taggableType IN $candidateTypeNames "
                            + "WITH r.taggableType AS type, r.taggableId AS id, "
                            + "sum(coalesce(r.affinity, 1.0)) AS score "
                            + "RETURN type, id, score ORDER BY score DESC, type, id LIMIT $limit",
                    Values.parameters(
                            "tagIds", tagIds.stream().map(UUID::toString).toList(),
                            "candidateTypeNames", candidateTypeNames,
                            "limit", limit));
            List<RankedTaggableRef> ranked = new ArrayList<>();
            for (Record record : result.list()) {
                ranked.add(new RankedTaggableRef(
                        new TaggableRef(record.get("type").asString(), UUID.fromString(record.get("id").asString())),
                        record.get("score").asDouble()));
            }
            return ranked;
        }
    }

    @Override
    public void upsertTagTextVector(TaggableRef ref, String text, EmbeddingVector vector) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        String property = qualifyTagText(vector.modelId());
        ensureTagTextVectorIndex(property, vector.dims());
        try (Session session = driver().session()) {
            session.run("MATCH (instance:`" + label + "` {id: $instanceId}) "
                            + "SET instance:" + TAG_TEXTED_LABEL + ", "
                            + "    instance.taggableType = $taggableType, "
                            + "    instance.taggableId = $taggableId, "
                            + "    instance.`" + property + "` = $vector, "
                            + "    instance.`" + property + "Text` = $text, "
                            + "    instance.`" + property + "ComputedAt` = $computedAt",
                    Values.parameters(
                            "instanceId", ref.taggableId().toString(),
                            "taggableType", ref.taggableType(),
                            "taggableId", ref.taggableId().toString(),
                            "vector", toDoubleList(vector.values()),
                            "text", text,
                            "computedAt", vector.computedAt().toString()))
                    .consume();
        }
    }

    @Override
    public void deleteTagTextVector(TaggableRef ref) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        try (Session session = driver().session()) {
            // Same posture as deleteTagSummaryVector: removing the shared label excludes the node from
            // every model's tag-text index; the model-qualified properties are left orphaned deliberately.
            session.run("MATCH (instance:`" + label + "` {id: $instanceId}) REMOVE instance:" + TAG_TEXTED_LABEL,
                    Values.parameters("instanceId", ref.taggableId().toString()))
                    .consume();
        }
    }

    @Override
    public String tagText(TaggableRef ref, String modelId) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        String property = qualifyTagText(modelId);
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (instance:`" + label + "`:" + TAG_TEXTED_LABEL + " {id: $instanceId}) "
                            + "RETURN instance.`" + property + "Text` AS text",
                    Values.parameters("instanceId", ref.taggableId().toString()));
            List<Record> records = result.list();
            if (records.isEmpty() || records.get(0).get("text").isNull()) {
                return null;
            }
            return records.get(0).get("text").asString();
        }
    }

    @Override
    public EmbeddingVector tagTextVector(TaggableRef ref, String modelId) {
        String label = TypeNames.simpleNameOf(ref.taggableType());
        String property = qualifyTagText(modelId);
        try (Session session = driver().session()) {
            var result = session.run(
                    "MATCH (instance:`" + label + "`:" + TAG_TEXTED_LABEL + " {id: $instanceId}) "
                            + "RETURN instance.`" + property + "` AS vector, "
                            + "instance.`" + property + "ComputedAt` AS computedAt",
                    Values.parameters("instanceId", ref.taggableId().toString()));
            List<Record> records = result.list();
            if (records.isEmpty() || records.get(0).get("vector").isNull()) {
                return EmbeddingVector.absent();
            }
            List<Object> stored = records.get(0).get("vector").asList();
            float[] values = new float[stored.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = ((Number) stored.get(i)).floatValue();
            }
            return new EmbeddingVector(values, modelId, values.length,
                    Instant.parse(records.get(0).get("computedAt").asString()));
        }
    }

    @Override
    public List<RankedTaggableRef> nearestByTagTextVector(EmbeddingVector reference, int n) {
        String property = qualifyTagText(reference.modelId());
        ensureTagTextVectorIndex(property, reference.dims());
        String indexName = tagTextVectorIndexName(property);
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
    public int tagTextVectorCount() {
        try (Session session = driver().session()) {
            Record record = session.run("MATCH (n:" + TAG_TEXTED_LABEL + ") RETURN count(n) AS c").single();
            return (int) record.get("c").asLong();
        }
    }

    private void ensureTaggregateIndexes() {
        if (!taggregateIndexesEnsured.compareAndSet(false, true)) {
            return;
        }
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE INDEX javai_taggregate_members_aggregate IF NOT EXISTS FOR (n:"
                        + TAGGREGATE_MEMBER_LABEL + ") ON (n.aggregateType, n.aggregateId)");
                tx.run("CREATE INDEX javai_taggregate_members_member IF NOT EXISTS FOR (n:"
                        + TAGGREGATE_MEMBER_LABEL + ") ON (n.memberType, n.memberId)");
                tx.run("CREATE INDEX javai_taggregate_pending_aggregate IF NOT EXISTS FOR (n:"
                        + TAGGREGATE_PENDING_LABEL + ") ON (n.aggregateType, n.aggregateId)");
                return null;
            });
        }
    }

    private void ensureTagTextVectorIndex(String property, int dims) {
        if (!tagTextVectorIndexesEnsured.add(property)) {
            return;
        }
        try (Session session = driver().session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE VECTOR INDEX `" + tagTextVectorIndexName(property) + "` IF NOT EXISTS "
                                + "FOR (n:" + TAG_TEXTED_LABEL + ") ON n.`" + property + "` "
                                + "OPTIONS {indexConfig: {`vector.dimensions`: $dims, `vector.similarity_function`: 'cosine'}}",
                        Values.parameters("dims", dims));
                return null;
            });
        }
    }

    private static String tagTextVectorIndexName(String property) {
        return "javai_" + TAG_TEXTED_LABEL.toLowerCase(java.util.Locale.ROOT) + "_" + property;
    }

    private static String qualifyTagText(String modelId) {
        return "tagTextVector__" + ModelIds.sanitize(modelId);
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
