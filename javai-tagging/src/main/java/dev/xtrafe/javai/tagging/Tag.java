package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Taggable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code @JavAIVectorizable @Taggable} -- a Tag needs a vector to participate in tag-similarity search
 * ({@link #slug} is the only vectorized field), and is itself recursively taggable (a Tag can carry Tags,
 * from the same or a different {@link TagSet}, via the same {@code JavAITagging.addTag(tag, otherTag)} call
 * used for any other {@code Taggable} instance -- no separate field or mechanism needed for that).
 *
 * <p>Persisted the same way any other {@code @Entity @JavAIVectorizable} class is -- via
 * {@code JavAIPI.repository(...)}, matching the e2e project's own {@code Article}/{@code Comment} pattern.
 * {@code javai-tagging} adds no new persistence machinery for Tag/TagSet themselves; only {@link Tagging}
 * (the association) and the tag-summary-vector index need backend-specific code of their own -- see
 * doc/spec/tagging.md's "Persistence, across all three backends".
 *
 * <p><b>Slug is fixed at creation, not settable afterward</b> -- no {@code setSlug} exists, deliberately (see
 * "Slug derivation and immutability" in doc/spec/tagging.md): it's derived once, in the real constructor, by
 * slugifying whichever localized display string is entered first. The no-arg constructor below exists only
 * for reflective hydration (matching every other backend-hydrated entity in this project); it leaves every
 * field at its default until the hydrating backend writes the persisted values back in directly.
 *
 * <p><b>Known limitation, by design</b>: slug derivation assumes the first-entered string is Latin-script.
 * A slug derived from a CJK, Arabic, or other non-Latin-script first entry has no clean lowercase-and-
 * hyphenate equivalent -- this library deliberately does not attempt transliteration or translation. Enter
 * the English (or otherwise Latin-script) form first for best results; nothing here enforces that.
 */
@Entity
@JavAIVectorizable
@Taggable
public class Tag implements dev.xtrafe.javai.tagging.Taggable {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("^-+|-+$");

    @Id
    private UUID id;

    @Vectorize
    private String slug;

    /** See {@link LocalizedNames}'s own javadoc for why this is a JSON string, not a {@code Map} field. */
    private String localizedNamesJson;

    private String description;

    @ManyToOne
    private TagSet tagSet;

    /** Reflective-hydration only -- see class javadoc. Application code should use the real constructor. */
    public Tag() {
    }

    public Tag(TagSet tagSet, String locale, String displayName) {
        this.id = UUID.randomUUID();
        this.tagSet = tagSet;
        Map<String, String> names = new LinkedHashMap<>();
        names.put(locale, displayName);
        this.localizedNamesJson = LocalizedNames.encode(names);
        this.slug = slugify(displayName);
        // Keeps the owning TagSet's own @Summary tags list in sync with its ManyToOne back-reference --
        // without this, tagSet.getTags() (what JavAITagging.classify() reads as candidates) would silently
        // stay empty for every Tag created after the TagSet itself, since nothing else registers it.
        tagSet.getTags().add(this);
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public Map<String, String> getLocalizedNames() {
        return LocalizedNames.decode(localizedNamesJson);
    }

    public void setLocalizedName(String locale, String displayName) {
        Map<String, String> names = getLocalizedNames();
        names.put(locale, displayName);
        localizedNamesJson = LocalizedNames.encode(names);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TagSet getTagSet() {
        return tagSet;
    }

    /**
     * Lowercase, non-alphanumeric runs collapsed to a single hyphen, leading/trailing hyphens trimmed --
     * e.g. {@code "Zero-Day / Supply Chain!"} -> {@code "zero-day-supply-chain"}. No transliteration: a
     * non-Latin-script input collapses to an empty or near-empty slug, an accepted limitation (see class
     * javadoc), not silently "fixed" here.
     */
    private static String slugify(String displayName) {
        String lower = displayName.toLowerCase(Locale.ROOT);
        String hyphenated = NON_ALPHANUMERIC.matcher(lower).replaceAll("-");
        return LEADING_TRAILING_HYPHENS.matcher(hyphenated).replaceAll("");
    }
}
