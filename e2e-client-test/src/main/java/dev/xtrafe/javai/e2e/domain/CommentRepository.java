package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.persistence.JavAIRepository;

/**
 * No derived queries of its own -- {@link Comment} is never queried independently in this project, only
 * reached via {@link Article#getFeaturedComment()}/{@link Article#getComments()}. Still needs to be
 * realized once via {@code JavAIPI.repository(CommentRepository.class)}, though: the Neo4j backend only
 * learns an entity type's node label the same way any repository's is registered, and it needs that
 * mapping to hydrate a related {@code Comment} node back into a real {@code Comment} object when
 * traversing {@code Article}'s {@code @Summary} relationships.
 */
public interface CommentRepository extends JavAIRepository<Comment> {
}
