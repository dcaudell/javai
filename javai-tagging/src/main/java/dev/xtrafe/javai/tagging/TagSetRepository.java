package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIRepository;

/**
 * See {@link TagRepository}'s own identical javadoc -- the same reasoning applies to {@link TagSet}.
 * Deliberately left unwrapped (unlike {@link Tag}): {@code TagSet} never participates in the
 * tagging-association machinery, so it needs no {@link JavAITagRepository}-style facade of its own -- plain
 * CRUD via this interface is the whole story.
 */
public interface TagSetRepository extends JavAIRepository<TagSet> {
}
