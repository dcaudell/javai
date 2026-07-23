package dev.xtrafe.javai.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** Fixture for the transaction tests (OMI-146). Deliberately minimal and non-vectorized: what's under test
 *  is which session/transaction a repository call runs on, not embedding behavior, and a plain entity is
 *  also what shared-{@code SessionFactory} mode supports without JavAI's own mapping-time hooks. */
@Entity
final class TestTxRecord {

    @Id
    private UUID id;

    private String label;

    TestTxRecord() {
    }

    TestTxRecord(String label) {
        this.label = label;
    }

    UUID getId() {
        return id;
    }

    String getLabel() {
        return label;
    }
}
