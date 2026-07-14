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
