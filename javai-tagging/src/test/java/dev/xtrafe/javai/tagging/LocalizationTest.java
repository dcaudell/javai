package dev.xtrafe.javai.tagging;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bulk localization of {@link Tag} and {@link TagSet}, and the slug derivation it drives (OMI-201).
 *
 * <h2>What is actually at stake</h2>
 *
 * Adding a whole translation bundle at once is the easy half. The hard half is that the slug is derived
 * <em>from</em> those names, and it is the only {@code @Vectorize} field either type has -- so it is the
 * entity's searchable identity. Deriving it from whichever key happened to lead the JSON would make identity
 * depend on serialization order; deriving nothing would leave an entity that looks real and can never be
 * found. Both are pinned below.
 *
 * <p>{@link Tag} and {@link TagSet} share one implementation, so most rules are asserted once against Tag
 * and spot-checked against TagSet rather than duplicated wholesale.
 */
class LocalizationTest {

    @Nested
    class BulkEntry {

        @Test
        void aWholeBundleCanBeSuppliedAtConstruction() {
            Tag tag = new Tag(new TagSet("topics"), ordered(
                    "en", "Zero-day", "fr", "Faille zero-day", "de", "Zero-Day-Lücke"));

            assertEquals("zero-day", tag.getSlug());
            assertEquals("Faille zero-day", tag.getLocalizedNames().get("fr"));
            assertEquals("Zero-Day-Lücke", tag.getLocalizedNames().get("de"));
        }

        @Test
        void aWholeBundleCanBeSuppliedAsJson() {
            Tag tag = Tag.fromLocalizedNamesJson(new TagSet("topics"), """
                    {"en": "Zero-day", "fr": "Faille zero-day", "ja": "ゼロデイ"}""");

            assertEquals("zero-day", tag.getSlug());
            assertEquals("ゼロデイ", tag.getLocalizedNames().get("ja"));
        }

        @Test
        void tagSetTakesTheSameTwoForms() {
            TagSet fromMap = new TagSet(ordered("en", "Severity", "fr", "Gravité"));
            TagSet fromJson = TagSet.fromLocalizedNamesJson("""
                    {"en": "Severity", "fr": "Gravité"}""");

            assertEquals("severity", fromMap.getSlug());
            assertEquals("severity", fromJson.getSlug());
            assertEquals("Gravité", fromJson.getLocalizedNames().get("fr"));
        }

        /** Merge, not replace -- so a pipeline delivering one language at a time is additive. */
        @Test
        void alaterBundleMergesRatherThanReplacing() {
            Tag tag = new Tag(new TagSet("topics"), ordered("en", "Urgent", "fr", "Urgent (FR)"));

            tag.setLocalizedNames(ordered("de", "Dringend", "fr", "Pressant"));

            assertEquals("Urgent", tag.getLocalizedNames().get("en"), "an untouched locale must survive");
            assertEquals("Pressant", tag.getLocalizedNames().get("fr"), "a repeated locale is replaced");
            assertEquals("Dringend", tag.getLocalizedNames().get("de"));
            assertEquals(3, tag.getLocalizedNames().size());
        }

        @Test
        void theSingleLocaleSetterStillWorksAndStillMerges() {
            Tag tag = new Tag(new TagSet("topics"), ordered("en", "Urgent"));

            tag.setLocalizedName("fr", "Urgent (FR)");

            assertEquals("Urgent", tag.getLocalizedNames().get("en"));
            assertEquals("Urgent (FR)", tag.getLocalizedNames().get("fr"));
        }

        @Test
        void malformedJsonFailsAsThisLibrarysOwnErrorNotGsons() {
            TagSet tagSet = new TagSet("topics");

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> Tag.fromLocalizedNamesJson(tagSet, "{not json at all"));

            assertTrue(failure.getMessage().contains("JSON"), failure.getMessage());
        }
    }

    @Nested
    class SlugDerivation {

        /** Rule 2: English wins, wherever it sits in the bundle. */
        @Test
        void englishIsPreferredOverWhicheverEntryHappensToBeFirst() {
            Tag tag = new Tag(new TagSet("topics"), ordered(
                    "fr", "Faille zero-day", "de", "Zero-Day-Lücke", "en", "Zero-day"));

            assertEquals("zero-day", tag.getSlug(),
                    "identity must not depend on the order the bundle happened to arrive in");
            assertEquals("en", tag.getSlugLocale());
        }

        /** EN has varietals, and they count as English. */
        @Test
        void anEnglishVarietyCountsAsEnglish() {
            Tag hyphen = new Tag(new TagSet("a"), ordered("fr", "Gravité", "en-US", "Severity"));
            Tag underscore = new Tag(new TagSet("b"), ordered("fr", "Gravité", "en_GB", "Severity"));
            Tag upper = new Tag(new TagSet("c"), ordered("fr", "Gravité", "EN-AU", "Severity"));

            assertEquals("severity", hyphen.getSlug());
            assertEquals("severity", underscore.getSlug());
            assertEquals("severity", upper.getSlug());
            assertEquals("en_GB", underscore.getSlugLocale(), "the locale is recorded exactly as supplied");
        }

        /** A plain "en" beats a variety when both are present -- most likely what the author meant. */
        @Test
        void aPlainEnglishEntryBeatsAVariety() {
            Tag tag = new Tag(new TagSet("topics"), ordered(
                    "en-US", "Color", "en", "Colour", "fr", "Couleur"));

            assertEquals("colour", tag.getSlug());
            assertEquals("en", tag.getSlugLocale());
        }

        /** Rule 3: no English at all, so the first usable entry wins -- in the input's own order. */
        @Test
        void withoutEnglishTheFirstUsableEntryWins() {
            Tag tag = new Tag(new TagSet("topics"), ordered("fr", "Gravité", "de", "Schweregrad"));

            assertEquals("gravit", tag.getSlug());
            assertEquals("fr", tag.getSlugLocale());
        }

        /**
         * A candidate that slugifies to nothing is passed over rather than accepted, because a blank slug
         * and no slug are the same thing: an entity with an absent vector.
         */
        @Test
        void anUnslugifiableCandidateIsPassedOverForOneThatWorks() {
            Tag englishUnusable = new Tag(new TagSet("a"), ordered("en", "日本語", "fr", "Gravité"));

            assertEquals("gravit", englishUnusable.getSlug(),
                    "English is preferred, but only when it actually yields a slug");
            assertEquals("fr", englishUnusable.getSlugLocale());
        }

        /** Rule 1: identity, once established, is never re-derived. */
        @Test
        void anExistingSlugIsNeverRederivedByLaterLocalization() {
            Tag tag = new Tag(new TagSet("topics"), ordered("fr", "Gravité"));
            String slugBefore = tag.getSlug();

            tag.setLocalizedNames(ordered("en", "Severity"));

            assertEquals(slugBefore, tag.getSlug(),
                    "adding English later must not move the slug -- stored vectors and Taggings point at it");
            assertEquals("fr", tag.getSlugLocale(), "and the recorded source locale must not move either");
        }

        @Test
        void aTagSetGivenItsSlugDirectlyRecordsNoSlugLocale() {
            TagSet tagSet = new TagSet("severity");

            tagSet.setLocalizedNames(ordered("en", "Severity"));

            assertEquals("severity", tagSet.getSlug());
            assertEquals(null, tagSet.getSlugLocale(), "nothing was translated to derive it from");
        }
    }

    @Nested
    class SlugIsRequired {

        @Test
        void aBundleThatYieldsNoSlugIsRefused() {
            TagSet tagSet = new TagSet("topics");
            Map<String, String> unusable = ordered("ja", "日本語", "ar", "العربية");

            IllegalArgumentException failure =
                    assertThrows(IllegalArgumentException.class, () -> new Tag(tagSet, unusable));

            assertTrue(failure.getMessage().contains("ja"),
                    "the message must name what was tried: " + failure.getMessage());
            assertTrue(failure.getMessage().contains("transliterate"),
                    "and say why, since the fix is to supply a Latin-script name: " + failure.getMessage());
        }

        @Test
        void anEmptyBundleIsRefused() {
            TagSet tagSet = new TagSet("topics");

            assertThrows(IllegalArgumentException.class, () -> new Tag(tagSet, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> new TagSet(Map.of()));
        }

        @Test
        void aNullBundleIsRefused() {
            TagSet tagSet = new TagSet("topics");

            assertThrows(IllegalArgumentException.class, () -> new Tag(tagSet, (Map<String, String>) null));
        }

        @Test
        void aBlankLocaleKeyIsRefused() {
            TagSet tagSet = new TagSet("topics");

            assertThrows(IllegalArgumentException.class, () -> new Tag(tagSet, ordered("", "Urgent")));
        }

        @Test
        void aTagSetWithoutASlugIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> new TagSet((String) null));
            assertThrows(IllegalArgumentException.class, () -> new TagSet("  "));
        }
    }

    @Nested
    class LocalizedNamesAreNotALiveMap {

        /**
         * The returned map used to be a mutable copy, so this compiled, read correctly, and silently did
         * nothing -- exactly the call bulk localization invites.
         */
        @Test
        void mutatingTheReturnedMapFailsLoudlyInsteadOfSilentlyDoingNothing() {
            Tag tag = new Tag(new TagSet("topics"), ordered("en", "Urgent"));
            Map<String, String> names = tag.getLocalizedNames();

            assertThrows(UnsupportedOperationException.class, () -> names.put("fr", "Urgent (FR)"));
            assertEquals(1, tag.getLocalizedNames().size(), "and nothing was added");
        }

        @Test
        void tagSetIsTheSame() {
            TagSet tagSet = new TagSet(ordered("en", "Severity"));
            Map<String, String> names = tagSet.getLocalizedNames();

            assertThrows(UnsupportedOperationException.class, () -> names.put("fr", "Gravité"));
        }
    }

    /** A map whose iteration order is the order written, since rule 3 depends on it. */
    private static Map<String, String> ordered(String... localeThenName) {
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = 0; i < localeThenName.length; i += 2) {
            names.put(localeThenName[i], localeThenName[i + 1]);
        }
        return names;
    }
}
