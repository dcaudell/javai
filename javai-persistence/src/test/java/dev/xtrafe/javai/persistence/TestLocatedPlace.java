package dev.xtrafe.javai.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import org.springframework.data.geo.Point;

/** OMI-556: an entity inheriting its {@code Point} from {@link TestLocatedPlaceBase}. */
@Entity
class TestLocatedPlace extends TestLocatedPlaceBase {

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "details_id")
    private TestPlaceDetails details;

    TestLocatedPlace() {
    }

    TestLocatedPlace(String name, TestPlaceDetails details, Point coordinates) {
        super(name, coordinates);
        this.details = details;
    }

    TestPlaceDetails getDetails() {
        return details;
    }
}
