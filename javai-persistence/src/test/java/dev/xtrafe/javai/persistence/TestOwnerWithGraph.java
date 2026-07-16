package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.collections.JavAIKnowledgeGraph;
import dev.xtrafe.javai.collections.KnowledgeGraph;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * Neo4j-only fixture proving a {@code KnowledgeGraph<TestGraphNode, TestGraphEdge>} field round-trips
 * correctly -- see {@link RepositoryBackendNeo4jTest}'s KnowledgeGraph tests. {@code KnowledgeGraph} itself
 * stays a pure, persistence-unaware collection (see its own javadoc); everything here is an ordinary field
 * on an owning {@code @Entity}, exactly like {@link TestArticleWithTags}'s {@code Map} field.
 */
@Entity
final class TestOwnerWithGraph implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    private String title;

    private KnowledgeGraph<TestGraphNode, TestGraphEdge> graph = new JavAIKnowledgeGraph<>();

    TestOwnerWithGraph() {
    }

    TestOwnerWithGraph(String title) {
        this.title = title;
    }

    UUID getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    KnowledgeGraph<TestGraphNode, TestGraphEdge> getGraph() {
        return graph;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "title");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "title");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "title");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "title", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "title", reference);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type) {
        return JavAIRuntime.query(this, reference, type, Integer.MAX_VALUE);
    }

    @Override
    public <T> JavAIList<T> query(EmbeddingVector reference, Class<T> type, int maxDepth) {
        return JavAIRuntime.query(this, reference, type, maxDepth);
    }

    @Override
    public EmbeddingVector fieldVector(String fieldName) {
        return JavAIRuntime.fieldVector(this, fieldName);
    }
}
