package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;

interface TestArticleRepository extends JavAIRepository<TestArticle> {

    List<TestArticle> findNearestByTitleVector(EmbeddingVector reference, int limit);

    List<TestArticle> findNearestByVector(EmbeddingVector reference, int limit);

    List<TestArticle> findNearestBySummaryVector(EmbeddingVector reference, int limit);
}
