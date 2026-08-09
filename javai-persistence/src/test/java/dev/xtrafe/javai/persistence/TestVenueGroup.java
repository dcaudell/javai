package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** An owner of {@link TestVenue}s, so a venue can be reached through an association rather than loaded as
 *  the root of a repository call -- the two access paths OMI-271's follow-up question compares. */
@Entity
final class TestVenueGroup {

    @Id
    private UUID id;

    private String name;

    @OneToMany(cascade = CascadeType.ALL)
    private List<TestVenue> venues = new ArrayList<>();

    TestVenueGroup() {
    }

    TestVenueGroup(String name) {
        this.name = name;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    List<TestVenue> getVenues() {
        return venues;
    }
}
