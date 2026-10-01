package dev.xtrafe.javai.persistence;

import java.util.Collection;
import java.util.List;

/**
 * Repository interfaces that must be <b>refused at repository-creation time</b> (OMI-407), each isolating one
 * way of asking an {@code @Any} something it cannot answer. Grouped in one file for the same reason
 * {@link BogusTestArticleRepository} exists separately from the good ones: they are only ever passed to
 * {@code assertThrows}, never realized.
 */
final class BogusAnyRepositories {

    private BogusAnyRepositories() {
    }

    /** Traversing <em>into</em> an {@code @Any}: its targets live in different tables, so there is no join. */
    interface TraversesThroughAny extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findByCoverLabel(String label);
    }

    /** Ordering by one, for the same reason -- there is no single column to order on. */
    interface SortsByAny extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findByTitleOrderByCoverAsc(String title);
    }

    /** An operator an association cannot answer: there is no text in a discriminator + key pair to match. */
    interface MatchesAnyWithLike extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findByCoverContaining(String fragment);
    }

    /** {@code OfType} with an operator a type comparison has no meaning for. */
    interface OfTypeWithATextOperator extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findByCoverOfTypeContaining(String fragment);
    }

    /** {@code OfType} binding something that is not a {@code Class}. */
    interface OfTypeBindingTheWrongType extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findByCoverOfType(String discriminator);
    }

    /** {@code OfTypeIn} binding a single {@code Class} rather than a collection of them. */
    interface OfTypeInBindingASingleClass extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findByCoverOfTypeIn(Class<?> targetType);
    }

    /** The one genuinely ambiguous shape: the same {@code @Any} property named twice, keyword on only one. */
    interface OfTypeAmbiguouslyRepeated extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findByCoverOfTypeAndCoverIsNull(Class<?> targetType);
    }

    /** The keyword on a property that is not an {@code @Any} at all. */
    interface OfTypeOnANonAnyProperty extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findByTitleOfType(Class<?> targetType);
    }

    /** The keyword outside a narrowing predicate, in the vector convention. */
    interface OfTypeOutsideANarrowingPredicate extends JavAIRepository<TestAlbum> {
        List<TestAlbum> findNearestByTitleOfTypeVector(
                dev.xtrafe.javai.vector.EmbeddingVector reference, int limit);
    }

    /** A repository whose entity declares no {@code @Any} at all, so the hint has to say so. */
    interface OfTypeWhereNoAnyExists extends JavAIRepository<TestAccount> {
        List<TestAccount> findByUsernameOfType(Collection<Class<?>> targetTypes);
    }
}
