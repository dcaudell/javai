package dev.xtrafe.javai.tagging;

import java.util.UUID;

/**
 * {@code (taggableType, taggableId)} -- the heterogeneous handle used everywhere a tagged instance is
 * referenced without loading it (structural query results, the tag-similarity index). {@code taggableType}
 * is the simple class name, resolved the same way {@code JavAIRepository} resolves entity types;
 * {@code taggableId} is a {@code UUID}, matching {@code JavAIRepository}'s fixed identity convention. See
 * doc/spec/tagging.md's "Structural queries" for why a uniform identity column is what makes a single
 * generic {@code taggedWith} query possible per backend, rather than a per-type switch.
 */
public record TaggableRef(String taggableType, UUID taggableId) {
}
