package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.JavAIEmbeddingProvider;

import java.time.Instant;
import java.util.Locale;

/**
 * Deterministic like {@link FakeEmbeddingProvider}, but deliberately returns the same (near-identical)
 * vector for any text containing "urgent" or "pressing" -- letting {@code TagNearDuplicateDiagnosticTest}
 * construct two {@link Tag}s with genuinely different slugs whose vectors are still provably close, without
 * relying on {@link FakeEmbeddingProvider}'s own hash-based scheme (which has no semantic continuity: a
 * one-character difference in input text produces an unrelated hash, so it can't reliably produce a
 * near-duplicate pair on its own). Every other input still gets ordinary hash-based vectors, so unrelated
 * tags are provably NOT flagged as near-duplicates by the same scan.
 */
final class NearDuplicateEmbeddingProvider implements JavAIEmbeddingProvider {

    private static final String MODEL_ID = "fake-near-duplicate-model";
    private static final int DIMS = 8;

    @Override
    public EmbeddingVector embed(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        float[] values = new float[DIMS];
        if (normalized.contains("urgent") || normalized.contains("pressing")) {
            for (int i = 0; i < DIMS; i++) {
                values[i] = (i + 1) / (float) DIMS;
            }
        } else {
            int hash = text.hashCode();
            for (int i = 0; i < DIMS; i++) {
                values[i] = ((hash >>> (i * 4)) & 0xF) / 15f;
            }
        }
        return new EmbeddingVector(values, MODEL_ID, DIMS, Instant.now());
    }
}
