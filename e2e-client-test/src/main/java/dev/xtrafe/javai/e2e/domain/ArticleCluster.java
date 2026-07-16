package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.collections.JavAIKnowledgeGraph;
import dev.xtrafe.javai.collections.KnowledgeGraph;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * Neo4j-only fixture proving a {@code KnowledgeGraph<Article, RelatesTo>} <em>field</em> persists correctly
 * end to end, against a real Neo4j container -- see {@code KnowledgeGraphPersistenceE2ETest}. Deliberately a
 * separate entity from {@link Article} (not a field added to {@code Article} itself): {@code Article} is
 * shared across all three persistence backends' own e2e coverage, and a {@code KnowledgeGraph}-typed field
 * is Neo4j-only (see {@code RepositoryBackendNeo4j}'s {@code saveKnowledgeGraphField}/
 * {@code hydrateKnowledgeGraphField} and doc/spec/persistence-bridge.md's "{@code KnowledgeGraph} fields:
 * Neo4j-only") -- adding one directly to {@code Article} would break its own Postgres/MongoDB registration.
 *
 * <p>{@link #graph} stays exactly as pure as any other JavAI collection field -- a plain, hand-constructed
 * {@link JavAIKnowledgeGraph}, never a persistence-aware proxy. {@link Article} instances already implement
 * {@code JavAIGraphNode} (see that class's own javadoc), so they're reused directly as this graph's nodes.
 */
@Entity
@JavAIVectorizable
public class ArticleCluster {

    @Id
    private UUID id;

    @Vectorize
    private String name;

    private KnowledgeGraph<Article, RelatesTo> graph = new JavAIKnowledgeGraph<>();

    public ArticleCluster() {
    }

    public ArticleCluster(String name) {
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public KnowledgeGraph<Article, RelatesTo> getGraph() {
        return graph;
    }
}
