package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.FullText;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import dev.xtrafe.javai.vector.DirtyTrackingSupport;
import dev.xtrafe.javai.vector.EmbeddingVector;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * OMI-642's full-text fixture, shaped like a rules passage: prose searched by its words and by its meaning, a name
 * searched fuzzily, and a ruleset to narrow by. Hand-written {@code JavAIVectorizable}, as {@link TestAsset} is.
 */
@Entity
final class TestPassage implements JavAIVectorizable {

    @SuppressWarnings("unused") // reflectively accessed via JavAIRuntime.STATE_FIELD
    private transient DirtyTrackingSupport $javai$state;

    @Id
    private UUID id;

    @Vectorize
    @FullText
    @Column(columnDefinition = "TEXT")
    private String text;

    @FullText(language = "simple", fuzzy = true)
    private String name;

    private String ruleset;

    TestPassage() {
    }

    TestPassage(String name, String text, String ruleset) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.text = text;
        this.ruleset = ruleset;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getText() {
        return text;
    }

    void setText(String text) {
        String oldValue = this.text;
        this.text = text;
        JavAIRuntime.vectorizeFieldMutated(this, "text", oldValue, text);
    }

    String getRuleset() {
        return ruleset;
    }

    @Override
    public EmbeddingVector vector() {
        return JavAIRuntime.vector(this, "text");
    }

    @Override
    public EmbeddingVector concatenatedTextVector() {
        return JavAIRuntime.concatenatedTextVector(this, "text");
    }

    @Override
    public EmbeddingVector summaryVector() {
        return JavAIRuntime.summaryVector(this, "", "text");
    }

    @Override
    public double similarityTo(JavAIVectorizable other) {
        return JavAIRuntime.similarityToVectorizable(this, "text", other);
    }

    @Override
    public double similarityTo(EmbeddingVector reference) {
        return JavAIRuntime.similarityToReference(this, "text", reference);
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
