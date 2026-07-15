package dev.xtrafe.javai.tagging;

import java.util.UUID;

/**
 * {@code (taggableType, taggableId)} -- the heterogeneous handle used everywhere a tagged instance is
 * referenced without loading it (structural query results, the tag-similarity index). {@code taggableType}
 * is the tagged instance's <b>fully-qualified</b> class name ({@code instance.getClass().getName()}),
 * deliberately not the simple name -- see this module's own README, "TaggableRef.taggableType: fully-
 * qualified name, not simple name," for why (a real simple-name collision between two unrelated {@code Tag}
 * classes in this exact codebase is what surfaced the need). {@code taggableId} is a {@code UUID}, matching
 * {@code JavAIRepository}'s fixed identity convention. See doc/spec/tagging.md's "Structural queries" for
 * why a uniform identity column is what makes a single generic {@code taggedWith} query possible per
 * backend, rather than a per-type switch.
 */
public record TaggableRef(String taggableType, UUID taggableId) {
}
