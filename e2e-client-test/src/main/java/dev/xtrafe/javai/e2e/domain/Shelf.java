package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.UUID;

/**
 * The middle tier of the nesting fixture: a <b>woven</b> ({@code @JavAIVectorizable}, transformed by the
 * suite's real load-time weaver) Taggregate container of plain {@link Anthology}s -- the reverse lineage
 * mix from {@code Anthology}'s own plain-container-of-woven-members, so the two tiers together pin
 * heterogeneous container/member lineage in both directions from ordinary client code.
 *
 * <p>Deliberately <b>no</b> type-level {@code @Taggregate(concatenate = true)}: a shelf aggregates but does
 * not opt into the tag-text vector, pinning that aggregation and tag text stay independent opt-ins
 * ({@code tagText(shelf)} stays {@code null} while {@code taggingsOf(shelf)} carries aggregate rows).
 */
@Entity
@JavAIVectorizable
@dev.xtrafe.javai.annotations.Taggable
public class Shelf implements dev.xtrafe.javai.tagging.Taggable {

    @Id
    private UUID id;

    @Vectorize
    private String name;

    @OneToMany(cascade = CascadeType.ALL)
    @Taggregate
    private JavAIList<Anthology> anthologies = new JavAIArrayList<>();

    public Shelf() {
    }

    public Shelf(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public JavAIList<Anthology> getAnthologies() {
        return anthologies;
    }
}
