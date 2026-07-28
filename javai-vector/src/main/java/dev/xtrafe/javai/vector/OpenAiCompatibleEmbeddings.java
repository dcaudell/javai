package dev.xtrafe.javai.vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parsing for the OpenAI {@code /v1/embeddings} response shape, shared by {@link EmbeddingProviderOpenAI}
 * and {@link EmbeddingProviderVLlm} -- vLLM serves the identical wire contract, so the two had already begun
 * duplicating this (OMI-213).
 *
 * <p>Hand-rolled rather than pulled from a JSON library, consistent with every other provider in this
 * package: the shape is fixed and known.
 *
 * <pre>
 * {"object":"list",
 *  "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]},
 *          {"object":"embedding","index":1,"embedding":[0.3,0.4]}],
 *  "model":"text-embedding-3-small","usage":{...}}
 * </pre>
 *
 * <h2>Why {@code index} is not decoration</h2>
 *
 * OpenAI documents that {@code data} entries carry their input index and are <b>not</b> guaranteed to arrive
 * in request order. {@code embedAll}'s contract is one vector per input <em>in order</em>, so reading rows by
 * array position rather than by {@code index} would pair every text with the wrong vector whenever the server
 * reorders.
 *
 * <p>That failure mode is the reason this class exists rather than a few inline {@code indexOf} calls. It is
 * completely silent: every vector is individually well-formed and correctly dimensioned, nothing throws, and
 * the only symptom is a semantic index that returns subtly wrong neighbours forever. A slow loop is vastly
 * preferable to a fast wrong answer, so the ordering is treated as a checked invariant here rather than an
 * assumption -- {@link #parseIndexedRows} refuses a response whose indices are not exactly one each of
 * {@code 0..n-1}.
 */
final class OpenAiCompatibleEmbeddings {

    private OpenAiCompatibleEmbeddings() {
    }

    /**
     * Every embedding row in {@code data}, ordered by each entry's {@code index} field.
     *
     * <p>Falls back to array position only when <em>no</em> entry carries an index -- a server that reports
     * indices for some rows and not others is not something to guess about, and is rejected.
     *
     * @throws EmbeddingProviderOpenAI.EmbeddingProviderException if {@code data} is missing, an entry has no
     *         {@code embedding}, or the indices are not a permutation of {@code 0..n-1}
     */
    static List<float[]> parseIndexedRows(String responseBody) {
        List<String> entries = dataEntries(responseBody);
        if (entries.isEmpty()) {
            return List.of();
        }

        List<float[]> rows = new ArrayList<>(entries.size());
        int[] indices = new int[entries.size()];
        int indexedCount = 0;
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            rows.add(embeddingArray(entry, responseBody));
            int index = intField(entry, "index");
            indices[i] = index >= 0 ? index : i;
            if (index >= 0) {
                indexedCount++;
            }
        }

        if (indexedCount == 0) {
            return rows; // no index reported anywhere: positional order is all there is to go on
        }
        if (indexedCount != entries.size()) {
            throw new EmbeddingProviderOpenAI.EmbeddingProviderException(
                    "Embedding response reported \"index\" on only " + indexedCount + " of " + entries.size()
                            + " rows; ordering cannot be established: " + responseBody);
        }

        // A permutation of 0..n-1 exactly. Anything else (a duplicate, a gap, an out-of-range value) means
        // the rows cannot be placed with confidence, and placing them wrongly would never be noticed.
        boolean[] seen = new boolean[rows.size()];
        for (int index : indices) {
            if (index < 0 || index >= rows.size() || seen[index]) {
                throw new EmbeddingProviderOpenAI.EmbeddingProviderException(
                        "Embedding response indices are not one each of 0.." + (rows.size() - 1) + ": "
                                + Arrays.toString(indices) + " in " + responseBody);
            }
            seen[index] = true;
        }

        List<float[]> ordered = new ArrayList<>(java.util.Collections.nCopies(rows.size(), (float[]) null));
        for (int i = 0; i < rows.size(); i++) {
            ordered.set(indices[i], rows.get(i));
        }
        return ordered;
    }

    /** The first embedding row, for the single-text {@code embed} path. */
    static float[] parseFirstRow(String responseBody) {
        List<float[]> rows = parseIndexedRows(responseBody);
        if (rows.isEmpty()) {
            throw new EmbeddingProviderOpenAI.EmbeddingProviderException(
                    "Response carried no embeddings: " + responseBody);
        }
        return rows.get(0);
    }

    /** The raw JSON text of each top-level object inside {@code "data": [...]}. */
    private static List<String> dataEntries(String responseBody) {
        String key = "\"data\"";
        int keyIndex = responseBody.indexOf(key);
        if (keyIndex < 0) {
            throw new EmbeddingProviderOpenAI.EmbeddingProviderException(
                    "Response missing \"data\" field: " + responseBody);
        }
        int arrayStart = responseBody.indexOf('[', keyIndex + key.length());
        if (arrayStart < 0) {
            throw new EmbeddingProviderOpenAI.EmbeddingProviderException(
                    "Unexpected response shape: " + responseBody);
        }

        List<String> entries = new ArrayList<>();
        int depth = 0;
        int entryStart = -1;
        boolean inString = false;
        for (int i = arrayStart + 1; i < responseBody.length(); i++) {
            char c = responseBody.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // an escaped character can never close the string
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth == 0) {
                        entryStart = i;
                    }
                    depth++;
                }
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        entries.add(responseBody.substring(entryStart, i + 1));
                    }
                }
                case ']' -> {
                    if (depth == 0) {
                        return entries; // end of the data array
                    }
                }
                default -> { /* nothing outside an entry matters */ }
            }
        }
        throw new EmbeddingProviderOpenAI.EmbeddingProviderException(
                "Unterminated \"data\" array: " + responseBody);
    }

    /** The {@code "embedding":[...]} array of one {@code data} entry. */
    private static float[] embeddingArray(String entry, String wholeBody) {
        String key = "\"embedding\"";
        int keyIndex = entry.indexOf(key);
        if (keyIndex < 0) {
            throw new EmbeddingProviderOpenAI.EmbeddingProviderException(
                    "Response missing \"embedding\" field: " + wholeBody);
        }
        int arrayStart = entry.indexOf('[', keyIndex + key.length());
        int arrayEnd = arrayStart < 0 ? -1 : entry.indexOf(']', arrayStart);
        if (arrayStart < 0 || arrayEnd < 0) {
            throw new EmbeddingProviderOpenAI.EmbeddingProviderException(
                    "Unexpected response shape: " + wholeBody);
        }
        String row = entry.substring(arrayStart + 1, arrayEnd).strip();
        if (row.isBlank()) {
            return new float[0];
        }
        String[] parts = row.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].strip());
        }
        return values;
    }

    /** A flat integer field of one entry, or -1 when absent. */
    private static int intField(String entry, String fieldName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)")
                .matcher(entry);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }
}
