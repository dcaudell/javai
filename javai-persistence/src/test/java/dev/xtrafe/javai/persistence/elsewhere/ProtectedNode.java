package dev.xtrafe.javai.persistence.elsewhere;

import java.util.UUID;

import dev.xtrafe.javai.collections.JavAIGraphNode;
import jakarta.persistence.Id;

/** A graph node type with only a {@code protected} no-arg constructor, outside JavAI's package (OMI-607). */
public class ProtectedNode implements JavAIGraphNode {

    @Id
    private UUID id;

    private String name;

    protected ProtectedNode() {
    }

    public ProtectedNode(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
