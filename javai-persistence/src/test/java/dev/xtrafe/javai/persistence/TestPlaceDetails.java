package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** The {@code @OneToOne} target shared by the OMI-556 geo-on-a-mapped-superclass fixtures. */
@Entity
class TestPlaceDetails {

    @Id
    private UUID id;

    private String note;

    TestPlaceDetails() {
    }

    TestPlaceDetails(String note) {
        this.note = note;
    }

    String getNote() {
        return note;
    }
}
