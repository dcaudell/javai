package dev.xtrafe.javai.persistence.scanned;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * Lives in its own package so OMI-214's scanning test can name a package containing nothing else.
 *
 * <p>Nothing references this type: no field declares it, no other fixture mentions it, and it has no
 * repository requested before the factory is built. Scanning is therefore the only thing that could
 * register it, which is precisely what the test needs to prove.
 *
 * <p>The package is deliberately narrow. The wider {@code dev.xtrafe.javai.persistence} test package holds
 * fixtures that exist to be <em>rejected</em> (a concrete-typed JavAI collection carrying {@code @OneToMany},
 * for one), and scanning validates everything it finds -- so pointing a scan at it would fail, correctly,
 * for reasons unrelated to what is being tested.
 */
@Entity
public class ScannedOnlyEntity {

    @Id
    private UUID id = UUID.randomUUID();

    private String label;

    public ScannedOnlyEntity() {
    }

    public ScannedOnlyEntity(String label) {
        this.label = label;
    }

    public UUID getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }
}
