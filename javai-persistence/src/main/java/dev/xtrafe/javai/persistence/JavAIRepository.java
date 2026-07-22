package dev.xtrafe.javai.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring-Data-style repository base, per doc/spec/persistence-bridge.md. A repository interface extends
 * this with one type parameter -- {@code interface ArticleRepository extends JavAIRepository<Article>} --
 * and is realized via {@link JavAIPI#repository(Class)}, never implemented by hand.
 *
 * <p>Identity is fixed to {@link UUID} for every persistable entity, recognized via a plain {@code @Id}
 * ({@code jakarta.persistence.Id}, already a transitive dependency through {@code hibernate-core}) field --
 * reused, not reinvented, specifically so the same annotation marks identity on both the Postgres and
 * Neo4j backends without requiring full JPA {@code @Entity} semantics on the Neo4j path.
 *
 * <p>Beyond this base CRUD contract, a repository interface may declare two kinds of derived query. First,
 * the vector convention {@code findNearestBy<Field>Vector(EmbeddingVector reference, int limit)} (or the
 * whole-object {@code findNearestByVector}/{@code findNearestBySummaryVector} variants) -- see {@link JavAIPI}'s
 * javadoc for the full naming rule. Second (OMI-138), ordinary Spring-Data-style relational finders --
 * {@code findBy<Field>}/{@code existsBy…}/{@code countBy…}/{@code deleteBy…} with the full {@code And}/{@code Or}
 * + operator + {@code OrderBy} + {@code Top}/{@code First} grammar, dynamic {@code Sort}/{@code Pageable}/
 * {@code Limit}, and {@code List}/{@code Optional}/single/{@code Stream}/{@code Page}/{@code Slice}/{@code long}/
 * {@code boolean} return adapters -- resolved against the entity's own mapped columns, so one repository serves
 * both an entity's relational access and its vector search (see {@link DerivedFinderQuery}). A non-vectorized
 * {@code @Entity} is served by exactly this same path. Either kind is validated, and anything matching neither
 * is rejected, at repository-creation time -- never on first call.
 */
public interface JavAIRepository<T> {

    /** Insert-or-update, auto-vectorized: every {@code @Vectorize}/{@code @Summary} vector is recomputed
     *  and persisted alongside the entity itself, in the same transaction where the backend supports one. */
    T save(T entity);

    Optional<T> findById(UUID id);

    List<T> findAll();

    void deleteById(UUID id);

    /**
     * Re-embeds and re-persists every existing entity of this type under whichever
     * {@code JavAIEmbeddingProvider}/model is *currently* configured -- the explicit trigger for
     * "I swapped providers, now go re-vectorize everything." Since both backends store each model's
     * vectors under a name qualified by that model (a per-model Postgres table; a per-model-qualified
     * Neo4j property) and {@link #save} always writes under the currently-configured model, this leaves
     * every *other* model's previously-written vectors completely untouched -- reverting the configured
     * provider back to one used before therefore needs no reindexing at all, since that model's data was
     * never overwritten in the first place.
     */
    void reindexAll();
}
