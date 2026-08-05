package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * {@link TestAsset} queries that narrow by nothing -- the subset of the OMI-230 surface every backend
 * supports, including Neo4j.
 *
 * <p>Separate from {@link TestAssetRepository} because that interface declares narrowed methods, and on
 * Neo4j declaring one is itself the error: the repository cannot be created at all. Proving that Neo4j still
 * ranks, still reports similarities and still pages therefore needs an interface that asks for none of what
 * Neo4j refuses.
 */
interface TestAssetUnnarrowedRepository extends JavAIRepository<TestAsset> {

    List<TestAsset> findNearestByCaptionVector(EmbeddingVector reference, int limit);

    List<Ranked<TestAsset>> findNearestByCaptionVector(EmbeddingVector reference, Pageable pageable);
}
