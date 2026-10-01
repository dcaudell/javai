package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Modifying;
import dev.xtrafe.javai.annotations.Query;
import org.springframework.data.repository.query.Param;

/**
 * A declared {@code delete} over an entity that is both vectorized and held in a {@code @Summary} container --
 * the shape that proves why a bulk {@code delete} statement is the wrong implementation (OMI-406). Executed as
 * one, it would trip the container's join-table foreign key and leave {@code javai_vectors__*} rows behind.
 */
interface TestBookDeclaredRepository extends JavAIRepository<TestBook> {

    @Modifying
    @Query("delete from TestBook b where b.title = :title")
    long deleteByTitleDeclared(@Param("title") String title);

    /** No {@code where} at all: the id resolution has to cope with a whole-table delete too. */
    @Modifying
    @Query("delete from TestBook")
    long deleteEveryBook();
}
