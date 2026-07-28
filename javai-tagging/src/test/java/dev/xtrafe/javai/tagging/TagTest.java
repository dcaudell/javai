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

    /**
     * A display name with no alphanumerics slugifies to the empty string, so a {@link Tag} whose only
     * {@code @Vectorize} field is blank -- and whose {@code summaryVector()} is therefore
     * {@link dev.xtrafe.javai.vector.EmbeddingVector#isAbsent() absent} -- is reachable through the ordinary
     * public constructor.
     *
     * <p>Worth pinning because that is the input that used to break tag-summary recomputation (OMI-218).
     * {@code JavAITagRepository} accumulated tag summaries into a bare {@code float[]}, which cannot express
     * absence: such a tag arriving first sized the accumulator to zero dimensions and stored a content-free
     * vector as a real tag-summary, and arriving after a present one threw
     * {@link ArrayIndexOutOfBoundsException} from inside the add loop. The arithmetic now goes through
     * {@code VectorMath}, which skips absent terms -- so what this test establishes is that the input was
     * never hypothetical.
     */
    @Test
    void aDisplayNameWithNoAlphanumericsSlugifiesToBlank() {
        TagSet tagSet = new TagSet("topics");
        Tag tag = new Tag(tagSet, "en", "!!!");

        assertEquals("", tag.getSlug(),
                "a blank slug means a blank @Vectorize field, which means an absent summary vector");
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
