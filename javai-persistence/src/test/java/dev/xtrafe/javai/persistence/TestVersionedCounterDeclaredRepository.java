package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Modifying;
import dev.xtrafe.javai.annotations.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** The two spellings of a bulk update against a {@code @Version} entity, so the difference is pinned rather
 *  than described (OMI-406). */
interface TestVersionedCounterDeclaredRepository extends JavAIRepository<TestVersionedCounter> {

    @Modifying
    @Query("update TestVersionedCounter c set c.tally = c.tally + 1 where c.id = :id")
    int bump(@Param("id") UUID id);

    @Modifying
    @Query("update versioned TestVersionedCounter c set c.tally = c.tally + 1 where c.id = :id")
    int bumpVersioned(@Param("id") UUID id);
}
