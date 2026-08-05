package dev.xtrafe.javai.e2e.domain;

import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.persistence.Ranked;
import dev.xtrafe.javai.vector.EmbeddingVector;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

/**
 * Narrowed vector search over {@link MediaNote} (OMI-230), declared as a consuming application would.
 *
 * <p><b>Postgres and MongoDB only.</b> Every narrowed method below is refused by the Neo4j backend at
 * repository-creation time -- deliberately, since its vector index answers only "the K nearest" and cannot
 * apply a predicate before choosing K. That is why {@code JavAIEnvironment} registers this repository for
 * two backends and not the third, and it is the honest per-backend boundary rather than an omission.
 */
public interface MediaNoteRepository extends JavAIRepository<MediaNote> {

    List<MediaNote> findNearestByCaptionVector(EmbeddingVector reference, int limit);

    List<MediaNote> findNearestByCaptionVectorAndKindIs(
            EmbeddingVector reference, int limit, MediaNote.Kind kind);

    List<MediaNote> findNearestByCaptionVectorAndKindInAndPublishedTrue(
            EmbeddingVector reference, int limit, Collection<MediaNote.Kind> kinds);

    List<Ranked<MediaNote>> findNearestByCaptionVectorAndKindIs(
            EmbeddingVector reference, MediaNote.Kind kind, Pageable pageable);
}
