package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** One possible {@code @Any} target. Shares no supertype with {@link TestLottieCover} beyond {@link TestCover}. */
@Entity
final class TestImageCover implements TestCover {

    @Id
    private UUID id;

    private String label;

    TestImageCover() {
    }

    TestImageCover(String label) {
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

    @Override
    public String getLabel() {
        return label;
    }
}
