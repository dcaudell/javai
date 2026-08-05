package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

/**
 * Every shape of the OMI-230 method-name idiom, declared once so repository-creation-time validation is
 * itself under test: this interface failing to be created is a test failure, and several cases below exist
 * only to prove a shape is <em>accepted</em>.
 *
 * @see TestAsset
 */
interface TestAssetRepository extends JavAIRepository<TestAsset> {

    /** The pre-OMI-230 shape, unchanged -- it must keep working exactly as it did. */
    List<TestAsset> findNearestByCaptionVector(EmbeddingVector reference, int limit);

    // ---- narrowing --------------------------------------------------------------------------------

    List<TestAsset> findNearestByCaptionVectorAndKindIs(EmbeddingVector reference, int limit, TestAsset.Kind kind);

    List<TestAsset> findNearestByCaptionVectorAndKindIn(
            EmbeddingVector reference, int limit, Collection<TestAsset.Kind> kinds);

    List<TestAsset> findNearestByCaptionVectorAndPublishedTrue(EmbeddingVector reference, int limit);

    List<TestAsset> findNearestByCaptionVectorAndRatingGreaterThan(
            EmbeddingVector reference, int limit, int rating);

    List<TestAsset> findNearestByCaptionVectorAndRatingBetween(
            EmbeddingVector reference, int limit, int lower, int upper);

    List<TestAsset> findNearestByCaptionVectorAndOwnerIsNull(EmbeddingVector reference, int limit);

    List<TestAsset> findNearestByCaptionVectorAndOwnerIsNotNull(EmbeddingVector reference, int limit);

    List<TestAsset> findNearestByCaptionVectorAndOwnerContaining(
            EmbeddingVector reference, int limit, String fragment);

    /** Two AND-ed conditions, to prove a group composes rather than only ever holding one atom. */
    List<TestAsset> findNearestByCaptionVectorAndKindIsAndPublishedTrue(
            EmbeddingVector reference, int limit, TestAsset.Kind kind);

    /** OR, which is where the OR-of-ANDs shape stops being theoretical. */
    List<TestAsset> findNearestByCaptionVectorAndKindIsOrRatingGreaterThan(
            EmbeddingVector reference, int limit, TestAsset.Kind kind, int rating);

    // ---- ranked returns ---------------------------------------------------------------------------

    /** Ranked, and bounded by a trailing {@link Limit} rather than an {@code int} -- both the return shape
     *  and the limit source differ from the plain overload above. */
    List<Ranked<TestAsset>> findNearestByCaptionVectorAndKindIs(
            EmbeddingVector reference, TestAsset.Kind kind, Limit limit);

    List<Ranked<TestAsset>> findNearestBySummaryVector(EmbeddingVector reference, int limit);

    // ---- paging -----------------------------------------------------------------------------------

    /** A Pageable supplies both the window and the offset, so no int limit is declared at all. */
    List<TestAsset> findNearestByCaptionVector(EmbeddingVector reference, Pageable pageable);

    List<TestAsset> findNearestByCaptionVectorAndKindIs(
            EmbeddingVector reference, TestAsset.Kind kind, Pageable pageable);
}
