package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import org.springframework.data.geo.Point;

import java.util.UUID;

/** OMI-556's control: {@link TestPlace}'s fields on one class, with no {@code @MappedSuperclass}. */
@Entity
class TestFlatPlace {

    @Id
    private UUID id;

    private String name;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "details_id")
    private TestPlaceDetails details;

    private Point coordinates;

    TestFlatPlace() {
    }

    TestFlatPlace(String name, TestPlaceDetails details, Point coordinates) {
        this.name = name;
        this.details = details;
        this.coordinates = coordinates;
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    TestPlaceDetails getDetails() {
        return details;
    }

    Point getCoordinates() {
        return coordinates;
    }
}
