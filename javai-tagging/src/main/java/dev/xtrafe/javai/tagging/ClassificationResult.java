package dev.xtrafe.javai.tagging;

import java.util.List;

/**
 * The outcome of one {@link JavAITagging#classify} call -- which of {@code tagSet}'s candidate tags the
 * classifier applied, and (optionally) the affinity/reasoning it returned for each. Tags previously applied
 * with {@code source = "auto"} for this same {@link TagSet} but not returned this time are removed (see
 * {@link JavAITagging#classify}'s own javadoc); {@code source = "manual"} taggings are never touched and so
 * never appear here.
 */
public record ClassificationResult(TaggableRef instance, TagSet tagSet, List<AppliedTag> appliedTags) {

    public record AppliedTag(Tag tag, Double affinity, String reasoning) {
    }
}
