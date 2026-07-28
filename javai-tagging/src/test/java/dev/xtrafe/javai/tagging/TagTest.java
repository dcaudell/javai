package dev.xtrafe.javai.tagging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slug derivation/immutability -- the state-machine-style claims doc/spec/tagging.md makes about {@link Tag}
 * that don't need a real embedding provider or a real backend to verify.
 */
class TagTest {

    @Test
    void slugIsDerivedFromTheFirstLocalizedNameEnteredAtCreation() {
        TagSet tagSet = new TagSet("severity");
        Tag tag = new Tag(tagSet, "en", "Zero-day");

        assertEquals("zero-day", tag.getSlug());
    }

    @Test
    void slugifyLowercasesAndCollapsesPunctuationAndWhitespaceToSingleHyphens() {
        TagSet tagSet = new TagSet("topics");
        Tag tag = new Tag(tagSet, "en", "Zero-Day / Supply Chain!!");

        assertEquals("zero-day-supply-chain", tag.getSlug());
    }

    @Test
    void slugHasNoLeadingOrTrailingHyphensEvenWhenTheSourceTextStartsOrEndsWithPunctuation() {
        TagSet tagSet = new TagSet("topics");
        Tag tag = new Tag(tagSet, "en", "  Urgent!  ");

        assertEquals("urgent", tag.getSlug());
    }

    /**
     * A display name with no alphanumerics yields no slug, and is now <b>refused at construction</b>
     * (OMI-201).
     *
     * <p>This used to succeed and produce a tag whose only {@code @Vectorize} field was blank -- and whose
     * vector was therefore absent, making it unsearchable and unclassifiable while looking like a real tag.
     * OMI-218 pinned that the input was reachable through the ordinary public constructor; OMI-201 makes it
     * an error, at the input that caused it rather than at some later read.
     */
    @Test
    void aDisplayNameThatYieldsNoSlugIsRefused() {
        TagSet tagSet = new TagSet("topics");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Tag(tagSet, "en", "!!!"));

        assertTrue(failure.getMessage().contains("slug"), failure.getMessage());
    }

    @Test
    void slugIsUnaffectedByLaterAddedLocalizedNames() {
        TagSet tagSet = new TagSet("topics");
        Tag tag = new Tag(tagSet, "en", "Urgent");
        String slugBefore = tag.getSlug();

        // Slug is derived once, at creation, from whichever locale is entered first -- adding more locales
        // afterward (a normal, expected operation) must never retroactively change it. There is no setSlug
        // at all (see Tag's own javadoc for why), so this is really just confirming adding a second locale
        // doesn't somehow reach the field through some other path.
        tag.setLocalizedName("fr", "Urgent (FR)");
        tag.setLocalizedName("de", "Dringend");

        assertEquals(slugBefore, tag.getSlug());
        assertEquals("Urgent (FR)", tag.getLocalizedNames().get("fr"));
        assertEquals("Dringend", tag.getLocalizedNames().get("de"));
    }

    @Test
    void tagBelongsToExactlyOneTagSetSetAtConstruction() {
        TagSet tagSet = new TagSet("topics");
        Tag tag = new Tag(tagSet, "en", "Urgent");

        assertSame(tagSet, tag.getTagSet());
    }

    @Test
    void tagIsGenuinelyTaggableAtRuntimeSinceTagsAreRecursivelyTaggable() {
        TagSet tagSet = new TagSet("topics");
        Tag tag = new Tag(tagSet, "en", "Urgent");

        assertInstanceOf(Taggable.class, tag);
    }
}
