package dev.xtrafe.javai.tagging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

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
