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

    // ---- ordinary relational derived finders (OMI-138 / OMI-141), exercised on all three backends ----

    List<Article> findByTitle(String title);

    boolean existsByTitle(String title);

    List<Article> findByTitleContaining(String fragment);

    List<Article> findByTitleRegex(String pattern);

    /** Nested filter through the singular {@code featuredComment} association onto an inherited
     *  ({@code @MappedSuperclass}) field. */
    List<Article> findByFeaturedCommentAuthor(String author);

    /** Nested filter through the to-many {@code comments} JavAI collection. */
    List<Article> findByCommentsAuthor(String author);

    List<Article> findByCommentsIsEmpty();

    long countByCommentsIsNotEmpty();
}
