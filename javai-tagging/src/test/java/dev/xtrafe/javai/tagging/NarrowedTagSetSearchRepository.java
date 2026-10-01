package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;

/**
 * Narrowed vector search over {@link TagSet}, narrowing through its {@code tags} collection (OMI-230).
 *
 * <p>The <b>to-many</b> counterpart to {@link NarrowedTagSearchRepository}, which narrows through a singular
 * {@code @ManyToOne}. Worth separating because the two reach the leaf differently -- a collection join rather
 * than a singular one -- and narrowing a vector search has to work over both. Nothing else in this feature's
 * tests exercises the to-many route. (Until OMI-277 the two were further apart still: {@code TagSet.tags} was
 * concrete-typed, so the predicate resolved as an id set rather than a Criteria join. Both are joins now.)
 */
interface NarrowedTagSetSearchRepository extends JavAIRepository<TagSet> {

    List<TagSet> findNearestBySlugVectorAndTagsSlugIs(EmbeddingVector reference, int limit, String tagSlug);

    List<TagSet> findNearestBySlugVector(EmbeddingVector reference, int limit);
}
