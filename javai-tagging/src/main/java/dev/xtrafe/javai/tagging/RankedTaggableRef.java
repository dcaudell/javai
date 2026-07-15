package dev.xtrafe.javai.tagging;

/** A {@link TaggableRef} paired with its cosine similarity to whatever reference vector a tag-summary-
 *  vector query was run against -- what {@link TaggingBackend#nearestByTagSummaryVector} returns, so
 *  {@link TagSimilarityVectorIndex#filterByMinSimilarity} can apply its own threshold without a second,
 *  separate backend query shape. */
record RankedTaggableRef(TaggableRef ref, double similarity) {
}
