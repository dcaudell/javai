package dev.xtrafe.javai.tagging;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Tag}/{@link TagSet}'s {@code localizedNames} is persisted as a single JSON-encoded string, not a
 * {@code Map<String, String>} field directly -- Hibernate has no automatic column mapping for an arbitrary
 * {@code Map} value type (confirmed empirically: {@code JdbcTypeRecommendationException}), and both the
 * Neo4j and MongoDB backends classify any {@code Map}-typed field as a set of relationships/references
 * (meant for entity-valued maps, per their own established field-classification conventions elsewhere in
 * {@code javai-persistence}), not a plain string-to-string one. A single opaque {@code String} column
 * sidesteps all three without touching any of that shared code.
 */
final class LocalizedNames {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() { }.getType();

    private LocalizedNames() {
    }

    static String encode(Map<String, String> localizedNames) {
        return GSON.toJson(localizedNames);
    }

    static Map<String, String> decode(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> decoded = GSON.fromJson(json, MAP_TYPE);
        return decoded == null ? new LinkedHashMap<>() : decoded;
    }
}
