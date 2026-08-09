package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAILinkedHashMap;
import dev.xtrafe.javai.model.JavAILinkedHashSet;
import dev.xtrafe.javai.model.JavAIList;
import dev.xtrafe.javai.model.JavAIMap;
import dev.xtrafe.javai.model.JavAISet;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;

import java.util.UUID;

/**
 * All three JavAI collection interfaces on one entity, in the only shape OMI-277 supports: declared by the
 * interface, non-final, with the ordinary JPA annotation.
 *
 * <p>Exists because that shape became <b>mandatory</b> rather than merely recommended, and two thirds of it
 * had never been exercised. Every fixture and consumer in this project used {@code JavAIList}; interface-
 * typed {@code JavAISet} and {@code JavAIMap} carrying an association annotation had no coverage anywhere,
 * even though {@code attachJavAICollectionTypes} handles all three and {@code validateMapKeyTypesAreSupported}
 * exists for the map case. Refusing the alternative without proving these would have been a poor trade.
 */
@Entity
final class TestCatalogue {

    @Id
    private UUID id;

    private String name;

    // Explicit join-table names: all three associations target TestBook, and Hibernate derives the default
    // name from owner + element type, so without these they would all claim test_catalogue_test_book.
    @OneToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "test_catalogue_ordered")
    private JavAIList<TestBook> ordered = new JavAIArrayList<>();

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(name = "test_catalogue_unique")
    private JavAISet<TestBook> unique = new JavAILinkedHashSet<>();

    /** A mapped {@code Map} needs JPA's own key column, exactly as a plain one would. */
    @OneToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "test_catalogue_by_code")
    @MapKeyColumn(name = "shelf_code")
    private JavAIMap<String, TestBook> byCode = new JavAILinkedHashMap<>();

    TestCatalogue() {
    }

    TestCatalogue(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    JavAIList<TestBook> getOrdered() {
        return ordered;
    }

    JavAISet<TestBook> getUnique() {
        return unique;
    }

    JavAIMap<String, TestBook> getByCode() {
        return byCode;
    }
}
