package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIRepository;

/**
 * No derived queries of its own -- {@link Tag} is never queried independently by slug/text the way a
 * domain entity's own repository might be; it's resolved by id (via {@link JavAITagRepository#tagsOf}) or
 * created directly. Still realized once per backend via {@code JavAIPI.repository(TagRepository.class,
 * config)}, both to get a usable proxy and (for Neo4j specifically) so that backend learns {@code Tag}'s
 * node label. {@link JavAITagRepository} wraps one of these as its own CRUD delegate.
 */
public interface TagRepository extends JavAIRepository<Tag> {
}
