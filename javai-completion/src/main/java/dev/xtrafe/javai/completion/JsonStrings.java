package dev.xtrafe.javai.completion;

/** Minimal JSON string-escaping helper for {@link CortexReplicate}'s hand-rolled request bodies -- mirrors
 *  {@code javai-vector}'s own package-private {@code JsonStrings} (not shared across modules, so
 *  duplicated here rather than exposed publicly from a module this one doesn't otherwise depend on for
 *  this reason alone). */
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
