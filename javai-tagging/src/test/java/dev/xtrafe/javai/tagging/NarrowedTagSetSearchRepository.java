package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;

/**
 * Narrowed vector search over {@link TagSet}, narrowing through its {@code tags} collection (OMI-230).
 *
 * <p>The <b>to-many</b> counterpart to {@link NarrowedTagSearchRepository}, which narrows through a singular
 * {@code @ManyToOne}. Worth separating because the two take different routes inside the Postgres backend:
 * {@code TagSet.tags} is declared by the <em>concrete</em> {@code JavAIArrayList} type, so it is stored in
 * {@code javai_collection_members} rather than as a native association, and a predicate reaching through it
 * resolves as an id set rather than a Criteria join. Narrowing a vector search must work over that route too,
 * and nothing else in this feature's tests exercises it.
 */
interface NarrowedTagSetSearchRepository extends JavAIRepository<TagSet> {

    List<TagSet> findNearestBySlugVectorAndTagsSlugIs(EmbeddingVector reference, int limit, String tagSlug);

    List<TagSet> findNearestBySlugVector(EmbeddingVector reference, int limit);
}
