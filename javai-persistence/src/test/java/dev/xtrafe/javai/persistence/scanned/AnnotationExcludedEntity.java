package dev.xtrafe.javai.persistence.scanned;

import dev.xtrafe.javai.annotations.PersistenceIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * Excluded by its own declaration rather than by anything in the configuration -- the case where the type's
 * owner would rather say "not JavAI's" once, at the class, than maintain a list elsewhere.
 */
@Entity
@PersistenceIgnore
public class AnnotationExcludedEntity {

    @Id
    private UUID id = UUID.randomUUID();

    private String label;
}
