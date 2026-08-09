package dev.xtrafe.javai.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code @ElementCollection} -- a collection of <em>basic values</em>, not entities, which is a mapping
 * JavAI's own collection machinery has nothing to do with and therefore has to leave alone.
 *
 * <p>Worth measuring rather than assuming: {@code validateCollectionFieldMapping} accepts the annotation
 * explicitly, so the intent is clearly that it works, but no fixture ever exercised one.
 */
@Entity
final class TestElementCollectionOwner {

    @Id
    private UUID id;

    private String label;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "test_element_collection_aliases")
    @Column(name = "alias")
    private List<String> aliases = new ArrayList<>();

    TestElementCollectionOwner() {
    }

    TestElementCollectionOwner(String label) {
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    List<String> getAliases() {
        return aliases;
    }
}
