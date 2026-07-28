package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;

interface TestChapterRepository extends JavAIRepository<TestChapter> {

    List<TestChapter> findNearestByConcatenatedTextVector(EmbeddingVector reference, int limit);

    List<TestChapter> findNearestBySummaryVector(EmbeddingVector reference, int limit);
}
