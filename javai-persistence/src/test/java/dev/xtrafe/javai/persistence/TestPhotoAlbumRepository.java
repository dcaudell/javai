package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;

import java.util.List;

interface TestPhotoAlbumRepository extends JavAIRepository<TestPhotoAlbum> {

    /** The method-name idiom's summary search -- it carries no model, and needs none (OMI-458). */
    List<TestPhotoAlbum> findNearestBySummaryVector(EmbeddingVector reference, int limit);
}
