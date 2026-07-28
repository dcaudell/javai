package dev.xtrafe.javai.persistence.scanned;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** Excluded by class, not by package -- it sits in the scanned package alongside types that are kept. */
@Entity
public class ExcludedByNameEntity {

    @Id
    private UUID id = UUID.randomUUID();

    private String label;
}
