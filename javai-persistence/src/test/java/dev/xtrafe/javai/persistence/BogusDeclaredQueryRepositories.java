package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Modifying;
import dev.xtrafe.javai.annotations.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Every declared query that must be <b>refused</b> (OMI-398), one interface each so a test names exactly the
 * mistake it is pinning. Only ever passed to {@code assertThrows}; never realized.
 */
final class BogusDeclaredQueryRepositories {

    private BogusDeclaredQueryRepositories() {
    }

    // ---- signature: caught by reflection alone, at repository-creation time ------------------------

    interface EmptyQuery extends JavAIRepository<TestCounterRow> {
        @Query("")
        List<TestCounterRow> nothing();
    }

    interface NamedParameterTheQueryNeverMentions extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c where c.label = :label")
        List<TestCounterRow> byLabel(@Param("nickname") String nickname);
    }

    interface NamedParameterTheMethodNeverSupplies extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c where c.label = :label and c.votes > :votes")
        List<TestCounterRow> byLabel(@Param("label") String label);
    }

    interface MixedNamedAndUnnamedParameters extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c where c.label = :label and c.votes > ?2")
        List<TestCounterRow> byLabel(@Param("label") String label, int votes);
    }

    interface WrongPositionalCount extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c where c.label = ?1 and c.votes > ?2")
        List<TestCounterRow> byLabel(String label);
    }

    interface NamedParametersWithoutAnyParamAnnotation extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c where c.label = :label")
        List<TestCounterRow> byLabel(String label);
    }

    interface PageWithoutACountQuery extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c where c.label = :label")
        Page<TestCounterRow> byLabel(@Param("label") String label, Pageable pageable);
    }

    interface CountQueryTakingDifferentParameters extends JavAIRepository<TestCounterRow> {
        @Query(value = "select c from TestCounterRow c where c.label = :label",
                countQuery = "select count(c) from TestCounterRow c where c.votes > :votes")
        Page<TestCounterRow> byLabel(@Param("label") String label, Pageable pageable);
    }

    interface CountQueryOnANonPageReturn extends JavAIRepository<TestCounterRow> {
        @Query(value = "select c from TestCounterRow c",
                countQuery = "select count(c) from TestCounterRow c")
        List<TestCounterRow> all();
    }

    interface SortOnAProjection extends JavAIRepository<TestCounterRow> {
        @Query("select c.label, count(c) from TestCounterRow c group by c.label")
        List<Object[]> counts(Sort sort);
    }

    interface DynamicSortOnANativeQuery extends JavAIRepository<TestCounterRow> {
        @Query(value = "select * from test_counter_row", nativeQuery = true)
        List<TestCounterRow> all(Sort sort);
    }

    interface BindableParameterAfterAPageable extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c where c.label = :label")
        List<TestCounterRow> byLabel(Pageable pageable, @Param("label") String label);
    }

    interface RawCollectionReturn extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c")
        @SuppressWarnings("rawtypes")
        List all();
    }

    interface ModifyingReturningEntities extends JavAIRepository<TestCounterRow> {
        @Modifying
        @Query("update TestCounterRow c set c.votes = 1")
        List<TestCounterRow> bump();
    }

    interface ModifyingWithAPageable extends JavAIRepository<TestCounterRow> {
        @Modifying
        @Query("update TestCounterRow c set c.votes = 1")
        int bump(Pageable pageable);
    }

    // ---- query text: caught once an ORM exists to parse it -----------------------------------------

    interface MalformedQuery extends JavAIRepository<TestCounterRow> {
        @Query("select c from TestCounterRow c where c.labelThatIsNotAField = :label")
        List<TestCounterRow> byLabel(@Param("label") String label);
    }

    interface UnknownEntityInQuery extends JavAIRepository<TestCounterRow> {
        @Query("select x from NoSuchEntityAnywhere x")
        List<TestCounterRow> nothing();
    }

    interface UpdateWithoutModifying extends JavAIRepository<TestCounterRow> {
        @Query("update TestCounterRow c set c.votes = 1")
        int bump();
    }

    interface ModifyingCarryingASelect extends JavAIRepository<TestCounterRow> {
        @Modifying
        @Query("select c from TestCounterRow c")
        int nothing();
    }

    // ---- writes JavAI must refuse because it maintains state derived from the column ---------------

    interface UpdatesAVectorizeField extends JavAIRepository<TestVectorizedCounter> {
        @Modifying
        @Query("update TestVectorizedCounter v set v.headline = :headline where v.id = :id")
        int rename(@Param("id") UUID id, @Param("headline") String headline);
    }

    /** The statement's target is resolved from the statement, not from the repository -- so a repository over
     *  a plain entity cannot be used as a way around the check. */
    interface PlainRepositoryUpdatingAVectorizedEntity extends JavAIRepository<TestCounterRow> {
        @Modifying
        @Query("update TestVectorizedCounter v set v.headline = :headline")
        int renameSomethingElse(@Param("headline") String headline);
    }

    interface UpdatesASummaryField extends JavAIRepository<TestShelf> {
        @Modifying
        @Query("update TestShelf s set s.books = null where s.id = :id")
        int detach(@Param("id") UUID id);
    }

    interface UpdatesATaggregateField extends JavAIRepository<TestTaggregateOwner> {
        @Modifying
        @Query("update TestTaggregateOwner o set o.member = null where o.id = :id")
        int detach(@Param("id") UUID id);
    }

    interface UpdatesAnExternalVectorKeyField extends JavAIRepository<TestImageAsset> {
        @Modifying
        @Query("update TestImageAsset a set a.contentHash = :hash where a.id = :id")
        int rehash(@Param("id") UUID id, @Param("hash") String hash);
    }

    interface NativeModifyingOnAVectorizedEntity extends JavAIRepository<TestVectorizedCounter> {
        @Modifying
        @Query(value = "update test_vectorized_counter set tally = tally + 1", nativeQuery = true)
        int bump();
    }

    interface NativeModifyingOnAGeoEntity extends JavAIRepository<TestVenue> {
        @Modifying
        @Query(value = "update test_venue set name = 'x'", nativeQuery = true)
        int rename();
    }
}
