package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;

import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;

import java.util.UUID;

/**
 * {@code @OneToMany(EAGER)} and {@code @ManyToMany(EAGER)} -- the eager halves of the collection cells.
 *
 * <p>Laziness was measured on both in OMI-275; the eager variants only on {@code @ManyToOne}. An eagerly
 * fetched collection is where "uniform attachment" has to hold without anyone asking for it: the members
 * arrive with the root whether the caller wanted them or not, so if any graph were going to come back
 * half-attached, this is a shape it could.
 *
 * <p>⚠️ Explicit {@code @JoinTable} names: both collections target {@link TestBook}, and Hibernate derives
 * the default from owner + element type, so without these they would claim the same table and the second
 * insert would fail on a NOT NULL column (OMI-277 hit exactly this twice).
 */
@Entity
final class TestEagerHub {

    @Id
    private UUID id;

    private String label;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinTable(name = "test_eager_hub_owned")
    private JavAIList<TestBook> owned = new JavAIArrayList<>();

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.EAGER)
    @JoinTable(name = "test_eager_hub_shared")
    private JavAIList<TestBook> shared = new JavAIArrayList<>();

    TestEagerHub() {
    }

    TestEagerHub(String label) {
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }

    JavAIList<TestBook> getOwned() {
        return owned;
    }

    JavAIList<TestBook> getShared() {
        return shared;
    }
}
