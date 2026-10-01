package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Modifying;
import dev.xtrafe.javai.annotations.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Declared queries over a <b>vectorized</b> entity -- the half of OMI-406 that must keep working.
 *
 * <p>Every method here writes or reads something no vector is derived from, so all of them are allowed. The
 * refusals live in {@link BogusDeclaredQueryRepositories}; both halves matter, because a guard that refused
 * these too would have blocked the feature on any entity that happens to carry an embedding.
 */
interface TestVectorizedCounterRepository extends JavAIRepository<TestVectorizedCounter> {

    @Query("select v from TestVectorizedCounter v where v.headline = :headline")
    List<TestVectorizedCounter> byHeadline(@Param("headline") String headline);

    /** An ordinary column on a vectorized entity: allowed, and the vector must survive it untouched. */
    @Modifying
    @Query("update TestVectorizedCounter v set v.tally = v.tally + 1 where v.id = :id")
    int incrementTally(@Param("id") UUID id);

    /** Same, for the column {@code save()} is not allowed to write. */
    @Modifying
    @Query("update TestVectorizedCounter v set v.protectedTally = v.protectedTally + 1 where v.id = :id")
    int incrementProtectedTally(@Param("id") UUID id);

    @Modifying
    @Query("delete from TestVectorizedCounter v where v.headline = :headline")
    long deleteByHeadline(@Param("headline") String headline);
}
