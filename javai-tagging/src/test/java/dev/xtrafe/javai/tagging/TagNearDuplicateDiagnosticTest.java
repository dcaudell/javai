package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.model.JavAIVectorizable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Hermetic -- no persistence needed at all, since the diagnostic this proves (doc/spec/tagging.md's
 * "Uniqueness": two different Tags in the same TagSet that mean nearly the same thing are never blocked at
 * creation, only discoverable via a {@code similarityTo()} scan over {@code tags()}) is pure in-memory
 * vector arithmetic + {@link TagSet#getTags()}'s own live membership list -- {@link Tag}'s constructor
 * already registers itself there without any repository involved.
 */
class TagNearDuplicateDiagnosticTest {

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new NearDuplicateEmbeddingProvider());
    }

    @Test
    void similarityToScanOverTagSetFindsNearDuplicatesWithoutBlockingCreation() {
        TagSet tagSet = new TagSet("priority-diagnostic");
        Tag urgent = new Tag(tagSet, "en", "Urgent");
        Tag pressing = new Tag(tagSet, "en", "Pressing");
        Tag unrelated = new Tag(tagSet, "en", "Unrelated Topic");

        // Creation of both near-duplicate-meaning tags succeeded, unblocked, coexisting in the same TagSet
        // under two genuinely different slugs -- exactly what the spec says this library never prevents.
        assertEquals(3, tagSet.getTags().size());
        assertNotEquals(urgent.getSlug(), pressing.getSlug());

        List<Tag> nearDuplicatesOfUrgent = tagSet.getTags().stream()
                .filter(candidate -> candidate != urgent)
                .filter(candidate -> ((JavAIVectorizable) urgent).similarityTo((JavAIVectorizable) candidate) > 0.99)
                .toList();

        assertEquals(1, nearDuplicatesOfUrgent.size(), "only 'pressing' should score as a near-duplicate of 'urgent'");
        assertEquals(pressing.getSlug(), nearDuplicatesOfUrgent.get(0).getSlug());
        assertNotEquals(unrelated.getSlug(), nearDuplicatesOfUrgent.get(0).getSlug());
    }
}
