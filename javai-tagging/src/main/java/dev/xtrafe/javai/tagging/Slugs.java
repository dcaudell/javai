package dev.xtrafe.javai.tagging;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Slug derivation, shared by {@link Tag} and {@link TagSet} (OMI-201).
 *
 * <p>Lived privately in {@link Tag} while it was the only type that derived a slug from a display string.
 * {@link TagSet} now derives one too -- from a localized name rather than being handed one -- so the rule
 * belongs somewhere both can reach rather than being copied.
 */
final class Slugs {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("^-+|-+$");

    private Slugs() {
    }

    /**
     * Lowercase, non-alphanumeric runs collapsed to a single hyphen, leading/trailing hyphens trimmed --
     * e.g. {@code "Zero-Day / Supply Chain!"} -> {@code "zero-day-supply-chain"}.
     *
     * <p><b>No transliteration.</b> A non-Latin-script input collapses to an empty string, which callers
     * treat as "no slug from this candidate" rather than silently accepting. This library deliberately does
     * not attempt transliteration or translation -- see doc/spec/tagging.md.
     *
     * @return the slug, or an empty string when the input yields nothing usable
     */
    static String slugify(String displayName) {
        if (displayName == null) {
            return "";
        }
        String lower = displayName.toLowerCase(Locale.ROOT);
        String hyphenated = NON_ALPHANUMERIC.matcher(lower).replaceAll("-");
        return LEADING_TRAILING_HYPHENS.matcher(hyphenated).replaceAll("");
    }
}
