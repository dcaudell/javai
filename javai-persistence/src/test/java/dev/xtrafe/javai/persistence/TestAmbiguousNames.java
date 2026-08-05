package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * A deliberately hostile set of names, for the one genuinely ambiguous thing about the OMI-230 method-name
 * grammar: {@code Vector} is the keyword that separates the field from the predicate, and it can also occur
 * inside either of them.
 *
 * <ul>
 *   <li>{@code subVector} -- a {@code @Vectorize} field whose own name ends in the keyword, so
 *       {@code findNearestBySubVectorVector} contains it twice and the <em>first</em> one is not the split.</li>
 *   <li>{@code vectorName} -- an ordinary scalar whose name begins with the keyword, so a predicate
 *       narrowing by it puts a third occurrence to the right of the real split.</li>
 * </ul>
 *
 * <p>Not a JPA entity and never persisted: name parsing happens against the class, so these tests need no
 * database at all.
 */
class TestAmbiguousNames {

    @Id
    private UUID id;

    @Vectorize
    private String caption;

    /** A vectorized field whose name ends in the keyword. */
    @Vectorize
    private String subVector;

    /** A plain scalar whose name starts with the keyword. */
    private String vectorName;

    UUID getId() {
        return id;
    }

    String getCaption() {
        return caption;
    }

    String getSubVector() {
        return subVector;
    }

    String getVectorName() {
        return vectorName;
    }
}
