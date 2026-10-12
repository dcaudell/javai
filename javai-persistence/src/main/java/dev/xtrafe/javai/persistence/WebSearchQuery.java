package dev.xtrafe.javai.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A full-text query in the syntax a person types (OMI-642), parsed once for the backends that must translate it:
 * words, {@code "quoted phrases"}, {@code -excluded} words or phrases, and {@code or} between alternatives. It is the
 * syntax Postgres' {@code websearch_to_tsquery} reads natively; Neo4j and MongoDB translate it from this.
 *
 * <p>Without {@code or}, every positive clause is required, as on Postgres. With {@code or} anywhere, the positive
 * clauses become alternatives -- a coarser reading than Postgres', which binds {@code or} to its two neighbours.
 */
record WebSearchQuery(List<Clause> clauses, boolean hasOr) {

    /** One word or phrase, required or excluded. */
    record Clause(String text, boolean phrase, boolean excluded) {
    }

    static WebSearchQuery parse(String query) {
        List<Clause> clauses = new ArrayList<>();
        boolean hasOr = false;
        int i = 0;
        int n = query.length();
        while (i < n) {
            char c = query.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            boolean excluded = false;
            if (c == '-' && i + 1 < n && !Character.isWhitespace(query.charAt(i + 1))) {
                excluded = true;
                i++;
                c = query.charAt(i);
            }
            if (c == '"') {
                int end = query.indexOf('"', i + 1);
                String phrase = end < 0 ? query.substring(i + 1) : query.substring(i + 1, end);
                i = end < 0 ? n : end + 1;
                if (!phrase.isBlank()) {
                    clauses.add(new Clause(phrase.strip(), true, excluded));
                }
                continue;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(query.charAt(i)) && query.charAt(i) != '"') {
                i++;
            }
            String word = query.substring(start, i);
            if (!excluded && word.equalsIgnoreCase("or")) {
                hasOr = true;
            } else if (!word.isBlank()) {
                clauses.add(new Clause(word, false, excluded));
            }
        }
        return new WebSearchQuery(List.copyOf(clauses), hasOr);
    }

    /** Whether anything is asked for: a query of exclusions alone matches nothing, as on Postgres. */
    boolean hasPositive() {
        return clauses.stream().anyMatch(clause -> !clause.excluded());
    }

    /** The words of {@code text}, lower-cased: letters and digits, split on everything else. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /**
     * The trigrams of {@code text} as {@code pg_trgm} forms them: each word padded with two spaces before and one
     * after, then every three-character window. What MongoDB's fuzzy search compares.
     */
    static List<String> trigrams(String text) {
        java.util.Set<String> grams = new java.util.LinkedHashSet<>();
        for (String word : words(text)) {
            String padded = "  " + word + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                grams.add(padded.substring(i, i + 3));
            }
        }
        return List.copyOf(grams);
    }
}
