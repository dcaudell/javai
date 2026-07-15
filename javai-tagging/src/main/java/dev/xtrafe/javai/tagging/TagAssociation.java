package dev.xtrafe.javai.tagging;

import java.util.UUID;

/**
 * Backend-internal shape for one {@link Tagging} row/relationship/array-entry -- {@code tagId}, not a
 * resolved {@link Tag}, since no {@link TaggingBackend} implementation has (or should need) a way to load a
 * Tag by id itself; {@link JavAITagRepository} resolves {@code tagId} through the caller-supplied
 * {@code JavAIRepository<Tag>} instead (see that class's own javadoc for why). Exists specifically for
 * {@link JavAITagRepository#classify}'s diff against existing {@code source = "auto"} associations, which needs
 * {@code affinity}/{@code source} alongside the tag id -- {@link TaggingBackend#tagIdsOf} alone isn't enough.
 */
record TagAssociation(UUID tagId, Double affinity, String source) {
}
