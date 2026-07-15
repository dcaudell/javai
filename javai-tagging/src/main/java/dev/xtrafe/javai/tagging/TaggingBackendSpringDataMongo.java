package dev.xtrafe.javai.tagging;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import dev.xtrafe.javai.persistence.JavAIPersistenceConfig;
import dev.xtrafe.javai.persistence.ModelIds;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An array of {@code {tagId, affinity, source, createdAt}} reference-pointer entries, embedded directly on
 * the tagged document itself (field {@value #TAGGINGS_FIELD}) -- matching the existing {@code {type, id}}
 * reference-pointer convention {@code RepositoryBackendSpringDataMongo} already uses for collection-typed
 * fields (see doc/spec/tagging.md's "Persistence, across all three backends"), rather than a separate
 * top-level collection the way Postgres/Neo4j realize {@link Tagging}.
 *
 * <p>{@code addTag} is a pull-then-push (remove any existing entry for this {@code tagId}, then add the
 * fresh one) rather than a single atomic update -- MongoDB has no simple single-operation "upsert one
 * array element matching a subdocument key" without more involved {@code arrayFilters} syntax, and two
 * sequential writes is an acceptable Phase 0 trade-off here (same "no dual-write / no cross-store
 * transaction" posture already documented elsewhere in this project) since both operations are idempotent
 * on their own.
 *
 * <p>The tagged document's own collection is located by {@link TypeNames#simpleNameOf}, matching
 * {@code RepositoryBackendSpringDataMongo}'s own {@code entityType.getSimpleName()} collection-naming
 * convention; this backend never creates that collection or document itself (same boundary as the Neo4j
 * backend -- see its own javadoc).
 */
final class TaggingBackendSpringDataMongo implements TaggingBackend {

    private static final String TAGGINGS_FIELD = "_javaiTaggings";

    /** A dedicated collection, not a field embedded on each tagged document's own per-type collection --
     *  deliberately, mirroring Postgres's own dedicated {@code javai_tag_summary_vectors__<model>} table
     *  rather than {@code RepositoryBackendSpringDataMongo}'s per-type collection convention. A single
     *  Atlas {@code $vectorSearch} index is scoped to one collection; keeping every tagged instance's
     *  summary vector here (regardless of its own entity type/collection) is what lets one index span every
     *  {@code @Taggable} type at once, the same goal Neo4j's shared {@code JavAITagged} label serves there. */
    private static final String TAG_SUMMARY_VECTORS_COLLECTION = "_javaiTagSummaryVectors";

    private final JavAIPersistenceConfig config;
    private final Set<String> tagSummaryVectorIndexesEnsured = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean tagSummaryVectorsUniqueIndexEnsured = new AtomicBoolean();
    private volatile MongoDatabase database;

    TaggingBackendSpringDataMongo(JavAIPersistenceConfig config) {
        this.config = config;
    }

    @Override
    public void addTag(TaggableRef ref, UUID tagId, Double affinity, String source) {
        MongoCollection<Document> collection = collectionFor(ref.taggableType());
        Document filter = new Document("_id", ref.taggableId().toString());
        collection.updateOne(filter, Updates.pull(TAGGINGS_FIELD, new Document("tagId", tagId.toString())));
        Document entry = new Document("tagId", tagId.toString())
                .append("affinity", affinity)
                .append("source", source)
                .append("createdAt", Instant.now().toString());
        collection.updateOne(filter, Updates.push(TAGGINGS_FIELD, entry));
    }

    @Override
    public void removeTag(TaggableRef ref, UUID tagId) {
        MongoCollection<Document> collection = collectionFor(ref.taggableType());
        collection.updateOne(new Document("_id", ref.taggableId().toString()),
                Updates.pull(TAGGINGS_FIELD, new Document("tagId", tagId.toString())));
    }

    @Override
    public boolean hasTag(TaggableRef ref, UUID tagId) {
        return tagIdsOf(ref).contains(tagId);
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
        MongoCollection<Document> collection = collectionFor(ref.taggableType());
        Document doc = collection.find(Filters.eq("_id", ref.taggableId().toString())).first();
        List<TagAssociation> associations = new ArrayList<>();
        if (doc == null) {
            return associations;
        }
        List<Document> taggings = doc.getList(TAGGINGS_FIELD, Document.class, List.of());
        for (Document tagging : taggings) {
            Double affinity = tagging.getDouble("affinity");
            associations.add(new TagAssociation(
                    UUID.fromString(tagging.getString("tagId")), affinity, tagging.getString("source")));
        }
        return associations;
    }

    @Override
    public List<TaggableRef> taggedWith(UUID tagId, List<String> candidateTypeNames) {
        List<TaggableRef> refs = new ArrayList<>();
        for (String typeName : candidateTypeNames) {
            MongoCollection<Document> collection = collectionFor(typeName);
            for (Document doc : collection.find(
                    Filters.elemMatch(TAGGINGS_FIELD, Filters.eq("tagId", tagId.toString())))) {
                refs.add(new TaggableRef(typeName, UUID.fromString(doc.getString("_id"))));
            }
        }
        return refs;
    }

    @Override
    public void upsertTagSummaryVector(TaggableRef ref, EmbeddingVector vector) {
        MongoCollection<Document> collection = tagSummaryVectorsCollection();
        String field = qualify(vector.modelId());
        ensureTagSummaryVectorIndex(field, vector.dims());
        Document filter = filterFor(ref);
        Document update = new Document("$set", new Document("taggableType", ref.taggableType())
                .append("taggableId", ref.taggableId().toString())
                .append(field, toDoubleList(vector.values()))
                .append(field + "ComputedAt", vector.computedAt().toString()));
        collection.updateOne(filter, update, new UpdateOptions().upsert(true));
    }

    @Override
    public void deleteTagSummaryVector(TaggableRef ref) {
        tagSummaryVectorsCollection().deleteOne(filterFor(ref));
    }

    @Override
    public List<RankedTaggableRef> nearestByTagSummaryVector(EmbeddingVector reference, int n) {
        String field = qualify(reference.modelId());
        ensureTagSummaryVectorIndex(field, reference.dims());
        String indexName = tagSummaryVectorIndexName(field);
        List<Bson> pipeline = List.of(
                new Document("$vectorSearch", new Document()
                        .append("index", indexName)
                        .append("path", field)
                        .append("queryVector", toDoubleList(reference.values()))
                        .append("numCandidates", Math.max(n * 10, 100))
                        .append("limit", n)),
                new Document("$project", new Document("taggableType", 1)
                        .append("taggableId", 1)
                        .append("score", new Document("$meta", "vectorSearchScore"))));
        List<RankedTaggableRef> ranked = new ArrayList<>();
        for (Document doc : tagSummaryVectorsCollection().aggregate(pipeline)) {
            TaggableRef ref = new TaggableRef(doc.getString("taggableType"), UUID.fromString(doc.getString("taggableId")));
            ranked.add(new RankedTaggableRef(ref, doc.getDouble("score")));
        }
        return ranked;
    }

    @Override
    public int tagSummaryVectorCount() {
        return (int) tagSummaryVectorsCollection().countDocuments();
    }

    private static Document filterFor(TaggableRef ref) {
        return new Document("taggableType", ref.taggableType()).append("taggableId", ref.taggableId().toString());
    }

    private MongoCollection<Document> tagSummaryVectorsCollection() {
        MongoCollection<Document> collection = database().getCollection(TAG_SUMMARY_VECTORS_COLLECTION);
        if (tagSummaryVectorsUniqueIndexEnsured.compareAndSet(false, true)) {
            collection.createIndex(Indexes.ascending("taggableType", "taggableId"), new IndexOptions().unique(true));
        }
        return collection;
    }

    private void ensureTagSummaryVectorIndex(String field, int dims) {
        String indexName = tagSummaryVectorIndexName(field);
        if (tagSummaryVectorIndexesEnsured.contains(indexName)) {
            return;
        }
        Document definition = new Document("fields", List.of(new Document("type", "vector")
                .append("path", field)
                .append("numDimensions", dims)
                .append("similarity", "cosine")));
        Document command = new Document("createSearchIndexes", TAG_SUMMARY_VECTORS_COLLECTION)
                .append("indexes", List.of(new Document("name", indexName)
                        .append("type", "vectorSearch")
                        .append("definition", definition)));
        createSearchIndexWithRetry(command);
        awaitIndexQueryable(indexName);
        tagSummaryVectorIndexesEnsured.add(indexName);
    }

    /** See {@code RepositoryBackendSpringDataMongo.createSearchIndexWithRetry}'s own identical javadoc --
     *  same empirically-confirmed transient "Search Index Management service" startup delay applies here. */
    private void createSearchIndexWithRetry(Document command) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (true) {
            try {
                database().runCommand(command);
                return;
            } catch (MongoCommandException e) {
                if (isDuplicateIndexError(e)) {
                    return;
                }
                if (!isSearchServiceNotYetReadyError(e) || Instant.now().isAfter(deadline)) {
                    throw e;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void awaitIndexQueryable(String indexName) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            try {
                for (Document index : database().getCollection(TAG_SUMMARY_VECTORS_COLLECTION).listSearchIndexes()) {
                    if (indexName.equals(index.getString("name")) && Boolean.TRUE.equals(index.getBoolean("queryable"))) {
                        return;
                    }
                }
            } catch (MongoCommandException e) {
                if (!isSearchServiceNotYetReadyError(e)) {
                    throw e;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException(
                "Vector search index '" + indexName + "' on '" + TAG_SUMMARY_VECTORS_COLLECTION
                        + "' did not become queryable within 90s");
    }

    private static boolean isDuplicateIndexError(MongoCommandException e) {
        String message = e.getErrorMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("already exist");
    }

    private static boolean isSearchServiceNotYetReadyError(MongoCommandException e) {
        String message = e.getErrorMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("search index management service");
    }

    private static String tagSummaryVectorIndexName(String field) {
        return "javai_tagsummaryvectors_" + field;
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

    private MongoCollection<Document> collectionFor(String taggableType) {
        return database().getCollection(TypeNames.simpleNameOf(taggableType));
    }

    private MongoDatabase database() {
        if (database == null) {
            synchronized (this) {
                if (database == null) {
                    MongoClient client = MongoClients.create(config.mongoUri());
                    database = client.getDatabase(config.mongoDatabase());
                }
            }
        }
        return database;
    }
}
