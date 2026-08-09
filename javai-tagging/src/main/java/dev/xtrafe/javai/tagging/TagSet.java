package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.Entity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * A dynamic, persisted collection of {@link Tag}s -- not a compile-time enum. {@code @Summary tags} means
 * {@code summaryVector()} is a free, decay-weighted aggregate over every member tag's own {@code vector()},
 * useful for comparing whole taxonomies to each other, not just individual tags. Persisted the same way any
 * other {@code @Entity @JavAIVectorizable} class is; see {@link Tag}'s own javadoc for why no new
 * persistence machinery is needed for this class.
 *
 * <p>A Tag's {@link Tag#getSlug() slug} is unique within its owning TagSet, not globally -- see
 * doc/spec/tagging.md's "Uniqueness" for why global uniqueness would be actively wrong here. This library
 * does not block two different Tags in the same TagSet whose slugs differ but whose meaning is nearly
 * identical; see that same section for why, and for the diagnostic-test idea (a {@code similarityTo()} scan
 * over {@link #getTags()}) this deliberately leaves as a caller-side concern rather than a blocking check.
 */
@Entity
@JavAIVectorizable
public class TagSet {

    @Id
    private UUID id;

    @Vectorize
    private String slug;

    /** Which locale's display string the slug was derived from (OMI-201) -- {@code null} when the slug was
     *  supplied directly via {@link #TagSet(String)} rather than derived from a localized name. */
    private String slugLocale;

    /** See {@link LocalizedNames}'s own javadoc for why this is a JSON string, not a {@code Map} field. */
    private String localizedNamesJson;

    /**
     * Declared by the interface and non-final, which is now the only supported shape for a JavAI collection
     * on an entity (OMI-277): Hibernate substitutes its own instance into a mapped collection field, and it
     * cannot substitute anything for a final concrete class. The annotation makes this a native association
     * on Postgres -- FK/join table, cascade, laziness -- and is inert on Neo4j and MongoDB, which classify a
     * field by its declared type and store references either way.
     */
    @Summary
    @OneToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    private JavAIList<Tag> tags = new JavAIArrayList<>();

    /** Reflective-hydration only -- application code should use a real constructor. */
    public TagSet() {
    }

    /** A tag set with an explicit slug and no localized names yet. {@link #getSlugLocale()} is
     *  {@code null} for one built this way: nothing was translated to derive it from. */
    public TagSet(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("A TagSet needs a slug: it is this type's only vectorized"
                    + " field, so without one the set cannot be searched. Either pass one, or use"
                    + " TagSet(Map) / TagSet.fromLocalizedNamesJson(String) to derive it from a name.");
        }
        this.id = UUID.randomUUID();
        this.slug = slug;
    }

    /**
     * A tag set with its whole translation bundle at once (OMI-201). The slug is derived from the English
     * entry if there is one, else the first entry that yields a usable slug -- see {@link LocalizedNames}.
     *
     * @throws IllegalArgumentException if no entry yields a usable slug
     */
    public TagSet(Map<String, String> localizedNames) {
        this.id = UUID.randomUUID();
        applyLocalization(LocalizedNames.merge(currentLocalization(), localizedNames, describe()));
    }

    /**
     * {@link #TagSet(Map)} from a JSON object of {@code {"locale": "display name"}} -- a static factory
     * rather than a constructor, so it cannot be confused with {@link #TagSet(String)}, which takes a slug.
     */
    public static TagSet fromLocalizedNamesJson(String localizedNamesJson) {
        return new TagSet(LocalizedNames.parse(localizedNamesJson, "a TagSet"));
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    /** The locale whose display string produced {@link #getSlug()}, or {@code null} if the slug was given
     *  directly rather than derived. */
    public String getSlugLocale() {
        return slugLocale;
    }

    /**
     * This set's display strings by locale.
     *
     * <p>Unmodifiable, and a snapshot rather than a view -- see {@link Tag#getLocalizedNames()} for why the
     * previously-mutable copy was a trap. Use {@link #setLocalizedNames(Map)} instead.
     */
    public Map<String, String> getLocalizedNames() {
        return Collections.unmodifiableMap(LocalizedNames.decode(localizedNamesJson));
    }

    /** Adds or replaces one locale's display string, leaving every other locale alone. */
    public void setLocalizedName(String locale, String displayName) {
        setLocalizedNames(Tag.singletonNames(locale, displayName));
    }

    /**
     * Merges a whole translation bundle in: a locale already present is replaced, every other one is left
     * alone. Derives the slug if this set does not have one yet, and never re-derives it if it does.
     *
     * @throws IllegalArgumentException if this set has no slug and none can be derived
     */
    public void setLocalizedNames(Map<String, String> localizedNames) {
        applyLocalization(LocalizedNames.merge(currentLocalization(), localizedNames, describe()));
    }

    /** {@link #setLocalizedNames(Map)} from a JSON object of {@code {"locale": "display name"}}. */
    public void setLocalizedNames(String localizedNamesJson) {
        applyLocalization(LocalizedNames.mergeJson(currentLocalization(), localizedNamesJson, describe()));
    }

    /**
     * ⚠️ Returns the {@code JavAIList} interface as of OMI-277, not {@code JavAIArrayList}. A caller that
     * declared the receiver as the concrete type needs a one-word change; every other use is unaffected,
     * since the interface carries the full {@code List} contract plus JavAI's own.
     */
    public JavAIList<Tag> getTags() {
        return tags;
    }

    private LocalizedNames.State currentLocalization() {
        return new LocalizedNames.State(localizedNamesJson, slug, slugLocale);
    }

    private void applyLocalization(LocalizedNames.State state) {
        this.localizedNamesJson = state.localizedNamesJson();
        this.slug = state.slug();
        this.slugLocale = state.slugLocale();
    }

    private String describe() {
        return slug == null || slug.isBlank() ? "a TagSet" : "TagSet '" + slug + "'";
    }
}
