package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.UUID;

/**
 * The top tier of the nesting fixture: a plain Taggregate container of {@link Shelf}s, completing the
 * taggregate-of-taggregate-of-taggregate chain ({@code Library -> Shelf -> Anthology -> Article/Comment}).
 * Its aggregate reads the shelves' own {@code source = "aggregate"} rows -- nesting composes one level at a
 * time, recursion-free -- and {@code concatenate = true} gives the whole library a tag-text vector derived,
 * transitively, from tags applied three levels down.
 */
@Entity
@dev.xtrafe.javai.annotations.Taggable
@Taggregate(concatenate = true)
public class Library implements dev.xtrafe.javai.tagging.Taggable {

    @Id
    private UUID id;

    private String name;

    @OneToMany(cascade = CascadeType.ALL)
    @Taggregate
    private JavAIList<Shelf> shelves = new JavAIArrayList<>();

    public Library() {
    }

    public Library(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public JavAIList<Shelf> getShelves() {
        return shelves;
    }
}
