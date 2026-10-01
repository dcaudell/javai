package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.persistence.JavAIRepository;

/** Ordinary realized repository for {@link TestAlbum} -- a Taggregate container is a persisted entity like
 *  any other now (OMI-304), so it needs one. */
public interface TestAlbumRepository extends JavAIRepository<TestAlbum> {
}
