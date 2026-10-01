package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import org.springframework.data.geo.Point;

/** OMI-556: a {@code Point} declared on an entity whose superclass is a {@code @MappedSuperclass}. */
@Entity
class TestPlace extends TestPlaceBase {

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "details_id")
    private TestPlaceDetails details;

    private Point coordinates;

    TestPlace() {
    }

    TestPlace(String name, TestPlaceDetails details, Point coordinates) {
        super(name);
        this.details = details;
        this.coordinates = coordinates;
    }

    TestPlaceDetails getDetails() {
        return details;
    }

    Point getCoordinates() {
        return coordinates;
    }
}
