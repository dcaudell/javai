package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import dev.xtrafe.javai.vector.Ranked;
import java.util.Collection;
import java.util.List;

/**
 * Predicates over the {@code @Any} association on {@link TestAlbum} (OMI-407) -- by target instance, and by
 * target <em>type</em> alone via the {@code OfType} keyword.
 *
 * <p>Deliberately separate from {@link TestAlbumRepository}, which {@link AnyAssociationTest} realizes against
 * Neo4j and MongoDB to assert they refuse an {@code @Any} field at registration. Method validation runs before
 * registration, so putting these finders there would change which error that test sees.
 */
interface TestAlbumQueryRepository extends JavAIRepository<TestAlbum> {

    // ---- by target instance: no new grammar, the @Any field is an ordinary property -----------------

    List<TestAlbum> findByCover(TestCover cover);

    List<TestAlbum> findByCoverNot(TestCover cover);

    List<TestAlbum> findByCoverIn(Collection<TestCover> covers);

    List<TestAlbum> findByCoverIsNull();

    List<TestAlbum> findByCoverIsNotNull();

    // ---- by target type: the OfType keyword ---------------------------------------------------------

    List<TestAlbum> findByCoverOfType(Class<?> targetType);

    List<TestAlbum> findByCoverOfTypeNot(Class<?> targetType);

    List<TestAlbum> findByCoverOfTypeIn(Collection<Class<?>> targetTypes);

    long countByCoverOfType(Class<?> targetType);

    boolean existsByCoverOfType(Class<?> targetType);

    List<TestAlbum> findByCoverOfTypeAndTitle(Class<?> targetType, String title);

    List<TestAlbum> findByTitleOrCoverOfType(String title, Class<?> targetType);

    List<TestAlbum> findByCoverOfTypeOrderByTitleAsc(Class<?> targetType);

    /** The same keyword inside the vector convention's narrowing tail -- proving one grammar, not two. */
    List<TestAlbum> findNearestByTitleVectorAndCoverOfType(
            EmbeddingVector reference, int limit, Class<?> targetType);

    List<Ranked<TestAlbum>> findNearestByTitleVectorAndCoverOfTypeIn(
            EmbeddingVector reference, int limit, Collection<Class<?>> targetTypes);
}
