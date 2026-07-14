package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;

/**
 * Realized via {@code JavAIPI.repository(ArticleRepository.class)} -- never implemented by hand, per
 * {@code javai-persistence}'s own contract. Backed by whichever {@code JavAIPersistenceConfig.Backend} is
 * configured (see {@code PersistenceE2ETest}, which exercises this against both Postgres and Neo4j).
 */
public interface ArticleRepository extends JavAIRepository<Article> {

    List<Article> findNearestByTitleVector(EmbeddingVector reference, int limit);

    List<Article> findNearestByBodyVector(EmbeddingVector reference, int limit);

    List<Article> findNearestByVector(EmbeddingVector reference, int limit);

    List<Article> findNearestBySummaryVector(EmbeddingVector reference, int limit);
}
