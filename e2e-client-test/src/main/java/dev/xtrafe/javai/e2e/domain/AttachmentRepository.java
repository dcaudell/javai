package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.persistence.JavAIRepository;

/**
 * No derived queries of its own, same as {@link CommentRepository} -- {@link Attachment} is only ever
 * reached via {@link Article#getAttachment()}. Still realized once via
 * {@code JavAIPI.repository(AttachmentRepository.class)}: Postgres needs {@code Attachment} added to
 * Hibernate's boot-time metadata before {@code Article}'s {@code @OneToOne} to it can be mapped at all,
 * and Neo4j needs its node label registered to hydrate a related {@code Attachment} node.
 */
public interface AttachmentRepository extends JavAIRepository<Attachment> {
}
