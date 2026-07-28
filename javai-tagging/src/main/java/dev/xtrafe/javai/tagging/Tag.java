package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Taggable;
import dev.xtrafe.javai.annotations.Vectorize;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code @JavAIVectorizable @Taggable} -- a Tag needs a vector to participate in tag-similarity search
 * ({@link #slug} is the only vectorized field), and is itself recursively taggable (a Tag can carry Tags,
 * from the same or a different {@link TagSet}, via the same {@code JavAITagRepository.addTag(tag, otherTag)} call
 * used for any other {@code Taggable} instance -- no separate field or mechanism needed for that).
 *
 * <p>Persisted the same way any other {@code @Entity @JavAIVectorizable} class is -- via
 * {@code JavAIPI.repository(...)}, matching the e2e project's own {@code Article}/{@code Comment} pattern.
 * {@code javai-tagging} adds no new persistence machinery for Tag/TagSet themselves; only {@link Tagging}
 * (the association) and the tag-summary-vector index need backend-specific code of their own -- see
 * doc/spec/tagging.md's "Persistence, across all three backends".
 *
 * <h2>Localizing a tag</h2>
 *
 * A whole translation bundle can be supplied at once, as a {@code Map} or as JSON (OMI-201):
 *
 * <pre>{@code
 * Tag tag = new Tag(tagSet, Map.of("en", "Zero-day", "fr", "Faille zero-day"));
 * Tag same = Tag.fromLocalizedNamesJson(tagSet, """
 *         {"en": "Zero-day", "fr": "Faille zero-day", "de": "Zero-Day-Lücke"}""");
 * tag.setLocalizedNames(Map.of("de", "Zero-Day-Lücke"));   // merges; existing locales stay
 * }</pre>
 *
 * <p><b>Slug is fixed once derived, not settable afterward</b> -- no {@code setSlug} exists, deliberately
 * (see "Slug derivation and immutability" in doc/spec/tagging.md). Later localization only adds names; it
 * never re-derives the slug, because the slug is both the vectorized identity and load-bearing for the
 * tag-similarity index, so changing it in place would strand stored vectors and Taggings. See
 * {@link LocalizedNames} for which localized string becomes the slug and why English is preferred.
 *
 * <p><b>A tag with no derivable slug is refused at creation</b>, rather than being allowed to exist and fail
 * later: the slug is its only {@code @Vectorize} field, so a slugless tag has an absent vector and can be
 * neither searched nor classified. The no-arg constructor below is the one exception -- it exists only for
 * reflective hydration (matching every other backend-hydrated entity in this project) and leaves every field
 * at its default until the hydrating backend writes the persisted values back in directly.
 *
 * <p><b>Known limitation, by design</b>: slug derivation does not transliterate. A CJK, Arabic, or other
 * non-Latin-script display string has no clean lowercase-and-hyphenate equivalent, so it yields no slug and
 * is passed over in favour of a candidate that does. Supply an English (or otherwise Latin-script) name and
 * it will be chosen automatically.
 */
@Entity
@JavAIVectorizable
@Taggable
public class Tag implements dev.xtrafe.javai.tagging.Taggable {

    @Id
    private UUID id;

    @Vectorize
    private String slug;

    /** Which locale's display string the slug was derived from (OMI-201) -- {@code null} only for a tag
     *  hydrated from a row written before this was recorded. */
    private String slugLocale;

    /** See {@link LocalizedNames}'s own javadoc for why this is a JSON string, not a {@code Map} field. */
    private String localizedNamesJson;

    private String description;

    @ManyToOne
    private TagSet tagSet;

    /** Reflective-hydration only -- see class javadoc. Application code should use a real constructor. */
    public Tag() {
    }

    public Tag(TagSet tagSet, String locale, String displayName) {
        this(tagSet, singletonNames(locale, displayName));
    }

    /**
     * A tag with its whole translation bundle at once. The slug is derived from the English entry if there
     * is one, else the first entry that yields a usable slug -- see {@link LocalizedNames}.
     *
     * @throws IllegalArgumentException if no entry yields a usable slug
     */
    public Tag(TagSet tagSet, Map<String, String> localizedNames) {
        this.id = UUID.randomUUID();
        this.tagSet = tagSet;
        applyLocalization(LocalizedNames.merge(currentLocalization(), localizedNames, describe()));
        // Keeps the owning TagSet's own @Summary tags list in sync with its ManyToOne back-reference --
        // without this, tagSet.getTags() (what JavAITagRepository.classify() reads as candidates) would silently
        // stay empty for every Tag created after the TagSet itself, since nothing else registers it.
        tagSet.getTags().add(this);
    }

    /**
     * {@link #Tag(TagSet, Map)} from a JSON object of {@code {"locale": "display name"}} -- a static factory
     * rather than a constructor, so it cannot be confused with {@code Tag(tagSet, locale, displayName)}.
     */
    public static Tag fromLocalizedNamesJson(TagSet tagSet, String localizedNamesJson) {
        return new Tag(tagSet, LocalizedNames.parse(localizedNamesJson, "a Tag"));
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    /** The locale whose display string produced {@link #getSlug()}, or {@code null} if not recorded. */
    public String getSlugLocale() {
        return slugLocale;
    }

    /**
     * This tag's display strings by locale.
     *
     * <p>Unmodifiable, and a snapshot rather than a view. It used to be a plain mutable copy, which made
     * {@code tag.getLocalizedNames().put(...)} compile, read correctly, and silently do nothing -- exactly
     * the call bulk localization invites. Use {@link #setLocalizedNames(Map)} instead.
     */
    public Map<String, String> getLocalizedNames() {
        return Collections.unmodifiableMap(LocalizedNames.decode(localizedNamesJson));
    }

    /** Adds or replaces one locale's display string, leaving every other locale alone. */
    public void setLocalizedName(String locale, String displayName) {
        setLocalizedNames(singletonNames(locale, displayName));
    }

    /**
     * Merges a whole translation bundle in: a locale already present is replaced, every other one is left
     * alone. Derives the slug if this tag does not have one yet, and never re-derives it if it does.
     *
     * @throws IllegalArgumentException if this tag has no slug and none can be derived
     */
    public void setLocalizedNames(Map<String, String> localizedNames) {
        applyLocalization(LocalizedNames.merge(currentLocalization(), localizedNames, describe()));
    }

    /** {@link #setLocalizedNames(Map)} from a JSON object of {@code {"locale": "display name"}}. */
    public void setLocalizedNames(String localizedNamesJson) {
        applyLocalization(LocalizedNames.mergeJson(currentLocalization(), localizedNamesJson, describe()));
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

    private LocalizedNames.State currentLocalization() {
        return new LocalizedNames.State(localizedNamesJson, slug, slugLocale);
    }

    private void applyLocalization(LocalizedNames.State state) {
        this.localizedNamesJson = state.localizedNamesJson();
        this.slug = state.slug();
        this.slugLocale = state.slugLocale();
    }

    /** Names this tag in a failure message as usefully as it can before it has an identity. */
    private String describe() {
        return slug == null || slug.isBlank() ? "a Tag" : "Tag '" + slug + "'";
    }

    static Map<String, String> singletonNames(String locale, String displayName) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(locale, displayName);
        return names;
    }
}
