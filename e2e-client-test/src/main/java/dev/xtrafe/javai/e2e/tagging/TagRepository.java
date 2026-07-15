package dev.xtrafe.javai.e2e.tagging;

import dev.xtrafe.javai.persistence.JavAIRepository;
import dev.xtrafe.javai.tagging.Tag;

/**
 * No derived queries of its own -- {@link Tag} is never queried independently by title/text the way
 * {@code ArticleRepository} is; it's resolved by id (via {@code JavAITagging.tagsOf(...)}) or created
 * directly. Still needs to be realized once via {@code JavAIPI.repository(TagRepository.class)} per
 * backend, both to get a usable proxy this module's own code can call and (for Neo4j specifically) so that
 * backend learns {@code Tag}'s node label -- see {@code CommentRepository}'s own identical javadoc for why.
 */
public interface TagRepository extends JavAIRepository<Tag> {
}
