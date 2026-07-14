package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

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

    /** See {@link LocalizedNames}'s own javadoc for why this is a JSON string, not a {@code Map} field. */
    private String localizedNamesJson;

    @Summary
    private final JavAIArrayList<Tag> tags = new JavAIArrayList<>();

    public TagSet() {
    }

    public TagSet(String slug) {
        this.id = UUID.randomUUID();
        this.slug = slug;
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

    public JavAIArrayList<Tag> getTags() {
        return tags;
    }
}
