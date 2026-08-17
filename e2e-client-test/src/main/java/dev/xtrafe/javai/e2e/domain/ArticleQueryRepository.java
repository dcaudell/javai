package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.annotations.Modifying;
import dev.xtrafe.javai.annotations.Query;
import dev.xtrafe.javai.persistence.JavAIRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Declared queries over the same {@link Article} graph {@link ArticleRepository} serves (OMI-398).
 *
 * <p><b>Why this is a second interface rather than more methods on {@link ArticleRepository}.</b> A `@Query`
 * is Postgres-only and is refused when the repository is <em>realized</em>, not when the method is called --
 * and {@code ArticleRepository} is realized against all three backends in this project's environment. Putting
 * a declared query there would therefore break the Neo4j and MongoDB proxies for every test, including ones
 * that never touch a declared query.
 *
 * <p>That generalises, and it is the shape an adopter should copy: <b>if an entity is served from more than
 * one backend, keep its declared queries in their own Postgres-only repository interface.</b> Both proxies
 * are independent (see doc/spec/persistence-bridge.md's "Persisting one entity type to more than one store"),
 * so the same entity is reachable through either.
 */
public interface ArticleQueryRepository extends JavAIRepository<Article> {

    // ---- the grouped aggregate the derived grammars cannot express ---------------------------------

    /** One count per article, over the real {@code JavAIList<Comment>} association. */
    @Query("select a.id, count(c) from Article a join a.comments c where a.id in :ids group by a.id")
    List<Object[]> commentCountsByArticle(@Param("ids") Collection<UUID> ids);

    /** The same aggregate, typed -- a JPQL constructor expression straight onto a record. */
    @Query("select new dev.xtrafe.javai.e2e.domain.ArticleCommentCount(a.id, count(c)) from Article a "
            + "join a.comments c where a.id in :ids group by a.id order by count(c) desc")
    List<ArticleCommentCount> typedCommentCountsByArticle(@Param("ids") Collection<UUID> ids);

    /** A scalar projection over a nested to-many -- ids only, no entities materialized. */
    @Query("select distinct a.id from Article a join a.comments c where c.author = :author")
    List<UUID> idsOfArticlesCommentedOnBy(@Param("author") String author);

    // ---- entity returns, so hydration can be measured on a genuinely woven class -------------------

    @Query("select a from Article a where a.title = :title")
    List<Article> byTitle(@Param("title") String title);

    @Query("select a from Article a where a.title = :title")
    Optional<Article> oneByTitle(@Param("title") String title);

    /** A query no derived name could carry: a join to a nested association plus an ordering. */
    @Query("select distinct a from Article a join a.comments c "
            + "where c.author like :authorPattern order by a.title asc")
    List<Article> commentedOnByAuthorLike(@Param("authorPattern") String authorPattern);

    @Query(value = "select a from Article a where a.title like :pattern",
            countQuery = "select count(a) from Article a where a.title like :pattern")
    Page<Article> pageByTitleLike(@Param("pattern") String pattern, Pageable pageable);

    @Query("select a from Article a where a.title like :pattern order by a.title asc")
    Slice<Article> sliceByTitleLike(@Param("pattern") String pattern, Pageable pageable);

    @Query("select a from Article a where a.title like :pattern")
    List<Article> sortedByTitleLike(@Param("pattern") String pattern, Sort sort);

    /** Positional binding, and a reach through a singular {@code @Summary} association. */
    @Query("select a from Article a where a.featuredComment.author = ?1")
    List<Article> byFeaturedCommentAuthor(String author);

    // ---- native ------------------------------------------------------------------------------------

    @Query(value = "select * from article where title = :title", nativeQuery = true)
    List<Article> nativeByTitle(@Param("title") String title);

    @Query(value = "select count(*) from article where title like :pattern", nativeQuery = true)
    long nativeCountByTitleLike(@Param("pattern") String pattern);

    // ---- targeted writes ---------------------------------------------------------------------------

    /** Allowed: an ordinary column on a woven, vectorized entity. The vector must survive untouched. */
    @Modifying
    @Query("update Article a set a.viewCount = a.viewCount + 1 where a.id = :id")
    int recordView(@Param("id") UUID id);

    /** Allowed, and the point of `@Column(updatable = false)`: only this path may write it. */
    @Modifying
    @Query("update Article a set a.likeCount = a.likeCount + 1 where a.id = :id")
    int recordLike(@Param("id") UUID id);

    @Modifying
    @Query("delete from Article a where a.title = :title")
    long deleteByTitleDeclared(@Param("title") String title);
}
