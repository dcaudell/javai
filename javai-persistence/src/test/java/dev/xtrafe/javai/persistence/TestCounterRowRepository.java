package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Modifying;
import dev.xtrafe.javai.annotations.Query;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Slice;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Declared queries (OMI-398) over a plain, non-vectorized entity -- reads, projections, and writes. */
interface TestCounterRowRepository extends JavAIRepository<TestCounterRow> {

    // ---- reads -------------------------------------------------------------------------------------

    @Query("select c from TestCounterRow c where c.label = :label order by c.votes desc")
    List<TestCounterRow> byLabel(@Param("label") String label);

    @Query("select c from TestCounterRow c where c.label = ?1 and c.votes >= ?2")
    List<TestCounterRow> byLabelAndMinimumVotes(String label, int minimumVotes);

    @Query("select c from TestCounterRow c where c.id = :id")
    Optional<TestCounterRow> oneById(@Param("id") UUID id);

    @Query("select c from TestCounterRow c where c.id = :id")
    TestCounterRow bareById(@Param("id") UUID id);

    /** Declared as a single result over a predicate that may match several -- the ambiguity must be reported. */
    @Query("select c from TestCounterRow c where c.label = :label")
    TestCounterRow oneByLabel(@Param("label") String label);

    @Query("select c from TestCounterRow c where c.label = :label")
    Stream<TestCounterRow> streamByLabel(@Param("label") String label);

    @Query("select count(c) from TestCounterRow c where c.label = :label")
    long tallyRowsFor(@Param("label") String label);

    /** The grouped aggregate the parent ticket opens with: one row per group, not one total. */
    @Query("select c.label, count(c) from TestCounterRow c where c.label in :labels group by c.label")
    List<Object[]> countsByLabel(@Param("labels") Collection<String> labels);

    @Query("select new dev.xtrafe.javai.persistence.TestLabelCount(c.label, count(c)) from TestCounterRow c "
            + "where c.label in :labels group by c.label order by c.label asc")
    List<TestLabelCount> typedCountsByLabel(@Param("labels") Collection<String> labels);

    @Query(value = "select c from TestCounterRow c where c.label = :label",
            countQuery = "select count(c) from TestCounterRow c where c.label = :label")
    Page<TestCounterRow> pageByLabel(@Param("label") String label, Pageable pageable);

    @Query("select c from TestCounterRow c where c.label = :label order by c.votes asc")
    Slice<TestCounterRow> sliceByLabel(@Param("label") String label, Pageable pageable);

    @Query("select c from TestCounterRow c where c.label = :label")
    List<TestCounterRow> sortedByLabel(@Param("label") String label, Sort sort);

    @Query("select c from TestCounterRow c where c.label = :label order by c.votes desc")
    List<TestCounterRow> limitedByLabel(@Param("label") String label, Limit limit);

    // ---- native ------------------------------------------------------------------------------------

    @Query(value = "select * from test_counter_row where label = :label order by votes desc",
            nativeQuery = true)
    List<TestCounterRow> nativeByLabel(@Param("label") String label);

    @Query(value = "select label, count(*) from test_counter_row where label = :label group by label",
            nativeQuery = true)
    List<Object[]> nativeCountsByLabel(@Param("label") String label);

    @Query(value = "select * from test_counter_row where label = :label order by votes asc", nativeQuery = true)
    List<TestCounterRow> nativePagedByLabel(@Param("label") String label, Pageable pageable);

    /** A native {@code Page}: the count query is native too, which is the one branch of the count path a
     *  JPQL-only fixture never reaches. */
    @Query(value = "select * from test_counter_row where label = :label order by votes asc",
            countQuery = "select count(*) from test_counter_row where label = :label",
            nativeQuery = true)
    Page<TestCounterRow> nativePageByLabel(@Param("label") String label, Pageable pageable);

    // ---- writes ------------------------------------------------------------------------------------

    /** The atomic read-modify-write a counter wants, and the reason {@code @Modifying} exists. */
    @Modifying
    @Query("update TestCounterRow c set c.tally = c.tally + 1 where c.id = :id")
    int incrementTally(@Param("id") UUID id);

    @Modifying
    @Query("update TestCounterRow c set c.tally = :tally where c.label = :label")
    long setTallyForLabel(@Param("label") String label, @Param("tally") long tally);

    @Modifying
    @Query("update TestCounterRow c set c.votes = c.votes + 1 where c.id = :id")
    void bumpVotes(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query("update TestCounterRow c set c.tally = c.tally + 1 where c.id = :id")
    int incrementTallyAndClear(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query("update TestCounterRow c set c.tally = c.tally + 1 where c.label = :label")
    int incrementTallyForLabelAfterFlush(@Param("label") String label);

    @Modifying
    @Query("delete from TestCounterRow c where c.label = :label")
    long deleteByLabelDeclared(@Param("label") String label);

    /** Native writes are allowed here precisely because JavAI keeps no storage of its own for this type. */
    @Modifying
    @Query(value = "update test_counter_row set tally = tally + 1 where id = :id", nativeQuery = true)
    int nativeIncrementTally(@Param("id") UUID id);
}
