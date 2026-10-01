package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAILinkedHashMap;
import dev.xtrafe.javai.model.JavAIMap;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.OneToMany;

import java.util.UUID;

/**
 * Interface-typed {@code JavAIMap} fields keyed by something other than {@code String} (OMI-279).
 *
 * <p>Postgres refused these until OMI-277. The key lived in the membership table's {@code varchar} column, so
 * a non-{@code String} key could only have been stored stringified and could never have round-tripped back
 * as its own type -- the validator's stated reason, and a good one while that storage existed. The storage
 * is gone, the validator went with it, and the key is now mapped by Hibernate exactly as JPA specifies. That
 * left the cell in an unhappy middle state: <b>no longer refused, and never measured</b>. Not refusing
 * something is not the same as supporting it, so this fixture exists to settle which one is true.
 *
 * <p>Three key types, spanning the two JPA mechanisms that map one: {@code @MapKeyColumn} alone for an
 * {@code Integer} and a {@code UUID}, and {@code @MapKeyEnumerated} beside it for an enum. Each is a
 * separate join table, named explicitly -- all three target {@link TestBook}, and Hibernate derives the
 * default name from owner plus element type, so unnamed they would silently share one.
 *
 * <p>{@code final} is deliberate and safe here, unlike on the target of a lazy to-one (see
 * {@code RemainingFetchCellsConformanceTest}): a collection is made lazy by a {@code PersistentCollection}
 * standing in for its <em>contents</em>, never by a proxy subclassing the owner, so nothing here needs to be
 * subclassable.
 */
@Entity
final class TestKeyedCatalogue {

    /** Deliberately not {@code String}-convertible-by-accident: two constants whose {@code name()} and
     *  {@code ordinal()} disagree about ordering, so a key silently stored either way is still detectable. */
    enum Grade {
        SILVER,
        GOLD
    }

    @Id
    private UUID id;

    private String name;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "test_keyed_by_rank")
    @MapKeyColumn(name = "rank_key")
    private JavAIMap<Integer, TestBook> byRank = new JavAILinkedHashMap<>();

    @OneToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "test_keyed_by_edition")
    @MapKeyColumn(name = "edition_key")
    private JavAIMap<UUID, TestBook> byEdition = new JavAILinkedHashMap<>();

    @OneToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "test_keyed_by_grade")
    @MapKeyColumn(name = "grade_key")
    @MapKeyEnumerated(EnumType.STRING)
    private JavAIMap<Grade, TestBook> byGrade = new JavAILinkedHashMap<>();

    TestKeyedCatalogue() {
    }

    TestKeyedCatalogue(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    JavAIMap<Integer, TestBook> getByRank() {
        return byRank;
    }

    JavAIMap<UUID, TestBook> getByEdition() {
        return byEdition;
    }

    JavAIMap<Grade, TestBook> getByGrade() {
        return byGrade;
    }
}
