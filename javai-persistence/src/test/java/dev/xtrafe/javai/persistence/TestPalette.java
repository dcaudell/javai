package dev.xtrafe.javai.persistence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;

/** Element collections of enums, keyed and plain: what a reflective save walk must treat as leaves (OMI-613). */
@Entity
class TestPalette {

    @Id
    private UUID id;

    private String name;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "test_palette_shade", joinColumns = @JoinColumn(name = "palette_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "color")
    @Enumerated(EnumType.STRING)
    @Column(name = "shade")
    private Map<TestColor, TestColor> shades = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "test_palette_color", joinColumns = @JoinColumn(name = "palette_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "color")
    private Set<TestColor> colors = new HashSet<>();

    TestPalette() {
    }

    TestPalette(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    Map<TestColor, TestColor> getShades() {
        return shades;
    }

    Set<TestColor> getColors() {
        return colors;
    }
}
