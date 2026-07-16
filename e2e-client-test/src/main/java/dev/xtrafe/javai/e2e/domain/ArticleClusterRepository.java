package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.persistence.JavAIRepository;

/**
 * Realized via {@code JavAIPI.repository(ArticleClusterRepository.class, config)} -- never implemented by
 * hand, per {@code javai-persistence}'s own contract. Neo4j-only in practice: see {@link ArticleCluster}'s
 * own javadoc for why its {@code KnowledgeGraph<Article, RelatesTo>} field can't be registered against the
 * Postgres or MongoDB backends.
 */
public interface ArticleClusterRepository extends JavAIRepository<ArticleCluster> {
}
