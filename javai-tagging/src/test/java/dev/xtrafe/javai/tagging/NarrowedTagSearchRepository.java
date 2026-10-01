package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.vector.Ranked;
import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;
import java.util.UUID;

/**
 * Narrowed vector search over {@link Tag} (OMI-230), declared the way a consuming application would declare
 * it rather than shipped on {@link TagRepository}.
 *
 * <p>{@code TagRepository} deliberately has no derived queries of its own -- {@code Tag} is resolved by id
 * through {@link JavAITagRepository}, not searched independently -- and that stays true. This interface
 * exists because the narrowing feature's most realistic consumer <em>is</em> a tag catalogue: "the tags
 * nearest this text, within this set" is the query, and the set is exactly the kind of narrowing that used
 * to force fetching a large N and discarding most of it.
 */
interface NarrowedTagSearchRepository extends JavAIRepository<Tag> {

    /** The realistic one: semantic search over a whole catalogue, restricted to one set. The predicate
     *  reaches through the {@code @ManyToOne} back-reference, so this also covers a nested property path. */
    List<Tag> findNearestBySlugVectorAndTagSetIdIs(EmbeddingVector reference, int limit, UUID tagSetId);

    List<Ranked<Tag>> findNearestBySlugVectorAndTagSetSlugIs(
            EmbeddingVector reference, int limit, String tagSetSlug);

    List<Tag> findNearestBySlugVectorAndDescriptionIsNotNull(EmbeddingVector reference, int limit);

    List<Tag> findNearestBySlugVector(EmbeddingVector reference, int limit);
}
