package dev.xtrafe.javai.persistence.elsewhere;

import java.util.UUID;

import dev.xtrafe.javai.collections.JavAIKnowledgeGraph;
import dev.xtrafe.javai.collections.KnowledgeGraph;
import jakarta.persistence.Id;

/**
 * An entity outside {@code dev.xtrafe.javai.persistence} whose only no-arg constructor is {@code protected}, as JPA
 * allows -- hydration must still be able to call it (OMI-607). Its graph's node and edge types are the same.
 */
public class ProtectedOwner {

    @Id
    private UUID id;

    private String title;

    private KnowledgeGraph<ProtectedNode, ProtectedEdge> graph = new JavAIKnowledgeGraph<>();

    protected ProtectedOwner() {
    }

    public ProtectedOwner(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public java.util.Set<ProtectedNode> nodes() {
        return graph.nodes();
    }

    public java.util.Set<ProtectedNode> neighbors(ProtectedNode node) {
        return graph.neighbors(node);
    }

    /** The edge type is package-private, so callers elsewhere link by reason. */
    public void link(ProtectedNode from, ProtectedNode to, String reason) {
        graph.addEdge(from, to, new ProtectedEdge(reason));
    }

    public java.util.Set<String> reasons(ProtectedNode from, ProtectedNode to) {
        java.util.Set<String> reasons = new java.util.HashSet<>();
        graph.edges(from, to).forEach(edge -> reasons.add(edge.reason()));
        return reasons;
    }
}
