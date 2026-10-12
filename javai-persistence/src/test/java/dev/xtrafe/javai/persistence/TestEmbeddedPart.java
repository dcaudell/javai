package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Embeddable;

/**
 * An {@code @Embeddable} value carrying a JavAI annotation (OMI-623). Nothing walks into an embedded value -- not the
 * weaver, not the runtime, not this module's reflection -- so the annotation would be silently ignored; registration
 * refuses it instead.
 */
@Embeddable
class TestEmbeddedPart {

    @Vectorize
    private String body;

    TestEmbeddedPart() {
    }

    TestEmbeddedPart(String body) {
        this.body = body;
    }
}
