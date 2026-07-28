package dev.xtrafe.javai.vector;

/**
 * The one piece of JSON handling shared by every {@link JavAIEmbeddingProvider} HTTP client in this package
 * ({@link EmbeddingProviderTextEmbeddingsInference}, {@link EmbeddingProviderOllama}) -- escaping a string for
 * embedding in a request body. Each client still hand-parses its own response shape rather than using a
 * general JSON library; see those classes' javadoc for why.
 */
final class JsonStrings {

    private JsonStrings() {
    }

    /**
     * A JSON array literal of escaped strings -- {@code ["a","b"]} -- for the batched embedding requests
     * (OMI-213). Every batching provider needs exactly this and nothing more, so it lives here rather than
     * being rebuilt inline four times.
     */
    static String stringArray(java.util.List<String> values) {
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                array.append(',');
            }
            array.append('"').append(escape(values.get(i))).append('"');
        }
        return array.append(']').toString();
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
