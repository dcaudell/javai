package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** The second {@code @Any} target -- a separate table, no relation to {@link TestImageCover} at all. */
@Entity
final class TestLottieCover implements TestCover {

    @Id
    private UUID id;

    private String label;

    TestLottieCover() {
    }

    TestLottieCover(String label) {
        this.id = UUID.randomUUID();
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }
}
