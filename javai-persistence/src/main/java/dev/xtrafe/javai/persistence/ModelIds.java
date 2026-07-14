package dev.xtrafe.javai.persistence;

import java.util.Locale;

/**
 * Turns an arbitrary {@code EmbeddingVector.modelId()} string into a safe, deterministic identifier
 * fragment -- used to name per-model Postgres tables and Neo4j properties/indexes, so vectors from
 * different models are structurally never mixed under the same name (not just a convention to remember).
 * Deterministic: the same {@code modelId} always sanitizes to the same fragment, so table/property
 * resolution is a pure function of which model produced a given {@code EmbeddingVector}.
 */
final class ModelIds {

    /** Conservative: Postgres identifiers cap at 63 bytes total, and the longest prefix this module uses
     *  ({@code javai_summary_vectors__}) is already 24 of those -- leaves comfortable room either way. */
    private static final int MAX_LENGTH = 32;

    private ModelIds() {
    }

    static String sanitize(String modelId) {
        String cleaned = modelId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        cleaned = cleaned.replaceAll("^_+", "").replaceAll("_+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "model";
        }
        if (cleaned.length() > MAX_LENGTH) {
            String suffix = Integer.toHexString(modelId.hashCode());
            cleaned = cleaned.substring(0, MAX_LENGTH - suffix.length() - 1) + "_" + suffix;
        }
        return cleaned;
    }
}
