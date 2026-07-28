package dev.xtrafe.javai.persistence.scanned.excluded;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/** Sits beneath the scanned package, in a sub-package the exclusion test names. Ordinary in every way. */
@Entity
public class ExcludedByPackageEntity {

    @Id
    private UUID id = UUID.randomUUID();

    private String label;
}
