package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Carries a query on a {@code JavAIRepository} method, for the questions the derived-name grammars cannot ask
 * (OMI-398).
 *
 * <p>The two existing grammars -- {@code findNearestBy<Field>Vector} and Spring Data's {@code PartTree}
 * finders -- cover a predicate over an entity's own properties and nothing else. A grouped aggregate is the
 * plainest thing they cannot express: "how many likes does each of these thirty assets have" is one
 * {@code GROUP BY}, while {@code countBy…In} returns a single total. The recourse without this was N+1
 * counts, loading every row to count in memory, or reaching around the repository to the
 * {@code SessionFactory} -- and that last one is what this annotation exists to stop being necessary.
 *
 * <pre>{@code
 * public interface LikeRepository extends JavAIRepository<Like> {
 *
 *     @Query("select new com.example.LikeCount(l.targetId, count(l)) from Like l "
 *             + "where l.targetId in :ids group by l.targetId")
 *     List<LikeCount> countsByTarget(@Param("ids") Collection<UUID> ids);
 * }
 * }</pre>
 *
 * <h2>Why this is JavAI's own annotation</h2>
 *
 * {@code org.springframework.data.jpa.repository.Query} would be the obvious thing to reuse -- and
 * {@code @Param} <em>is</em> reused, from {@code spring-data-commons}, which this project already depends on.
 * But {@code @Query} lives in {@code spring-data-jpa}, whose repository infrastructure JavAI does not use and
 * would be taking on a whole framework to borrow one annotation from. Import this one instead; the two can
 * coexist in a codebase that uses both, qualified where they meet.
 *
 * <h2>Backends</h2>
 *
 * <b>Postgres only.</b> Neo4j and MongoDB refuse a declared query at repository-creation time rather than
 * pretending: a JPQL string means nothing to either store, and translating one into Cypher or an aggregation
 * pipeline would be a query engine, not a bridge. This is the same explicit asymmetry
 * {@code JavAIPI.inTransaction} already carries.
 *
 * <h2>When it is validated</h2>
 *
 * The method's <em>signature</em> is validated at repository-creation time, like every other repository
 * method: parameter binding, return-type feasibility, trailing {@code Sort}/{@code Pageable}/{@code Limit}
 * placement. The <em>query text</em> is validated as soon as Hibernate can parse it -- immediately if the
 * {@code SessionFactory} exists, otherwise the moment it is built. Either way a malformed query fails before
 * any repository method runs, never on the first call of this one.
 *
 * @see Modifying for the write half
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Query {

    /** The query. JPQL by default; SQL when {@link #nativeQuery()} is true. */
    String value();

    /**
     * The query counting this one's total, required only for a {@code Page} return.
     *
     * <p>Not derived by rewriting {@link #value()}, deliberately: producing a count from an arbitrary select
     * means understanding its projection, its joins and its grouping, and a rewriter that is wrong is worse
     * than one that does not exist -- it answers a plausible number. A {@code Slice} return needs nothing
     * here, since it decides {@code hasNext} by fetching one extra row.
     */
    String countQuery() default "";

    /** Whether {@link #value()} is SQL rather than JPQL. */
    boolean nativeQuery() default false;
}
