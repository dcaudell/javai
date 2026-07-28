package dev.xtrafe.javai.tagging;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Every localization rule {@link Tag} and {@link TagSet} share, in one place (OMI-201).
 *
 * <p>The two entities are structurally identical here -- a JSON blob of {@code locale -> display string}, a
 * slug derived from one of those strings, and a record of which locale the slug came from -- so the logic
 * lives here and each entity holds only the three fields. They cannot share a superclass: both are JPA
 * {@code @Entity} types and an inherited mapped superclass would change their schema for no benefit.
 *
 * <h2>Why the names are one JSON string rather than a {@code Map} field</h2>
 *
 * Hibernate has no automatic column mapping for an arbitrary {@code Map} value type (confirmed empirically:
 * {@code JdbcTypeRecommendationException}), and both the Neo4j and MongoDB backends classify any
 * {@code Map}-typed field as a set of relationships/references (meant for entity-valued maps, per their own
 * established field-classification conventions elsewhere in {@code javai-persistence}), not a plain
 * string-to-string one. A single opaque {@code String} column sidesteps all three without touching any of
 * that shared code.
 *
 * <h2>Slug derivation</h2>
 *
 * The slug is the vectorized identity of a Tag or TagSet -- the only {@code @Vectorize} field either has --
 * and is fixed once derived. Feeding in a whole translation bundle makes "which string did the slug come
 * from" a question worth answering deliberately rather than by whichever key happened to lead the JSON, so:
 *
 * <ol>
 *   <li>An existing slug is never re-derived. Localizing an already-slugged entity only adds names.</li>
 *   <li>Otherwise the English entry wins -- {@code en}, or any variety of it ({@code en-US}, {@code en_GB},
 *       any case), preferring a plain {@code en} when both are present.</li>
 *   <li>Otherwise the first entry that yields a usable slug, in the input's own iteration order.</li>
 * </ol>
 *
 * <p>English is preferred rather than merely conventional because {@link Tag}'s slugifier has no
 * transliteration: a CJK or Arabic display string collapses to nothing usable. That is also why a candidate
 * that slugifies to blank is skipped rather than accepted -- a blank slug is indistinguishable from no slug,
 * and both are refused.
 *
 * <p><b>Order matters, so pass an ordered map.</b> Rule 3 reads the input's iteration order; a
 * {@link java.util.HashMap} has none worth relying on. JSON input is decoded into a {@link LinkedHashMap},
 * so a JSON object's key order is preserved and rule 3 means what it looks like it means.
 */
final class LocalizedNames {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() { }.getType();
    private static final String ENGLISH = "en";

    private LocalizedNames() {
    }

    /**
     * The localization state of a Tag or TagSet: the encoded names, the slug, and the locale the slug came
     * from ({@code null} when the slug was supplied directly rather than derived).
     */
    record State(String localizedNamesJson, String slug, String slugLocale) {

        static State of(String localizedNamesJson, String slug, String slugLocale) {
            return new State(localizedNamesJson, slug, slugLocale);
        }
    }

    /**
     * Merges {@code additions} into {@code current}'s names and derives a slug if there isn't one yet.
     *
     * <p>Merge, not replace: an entry for a locale already present is overwritten, every other locale is
     * left alone. That makes repeated partial imports additive, which is what a translation pipeline
     * delivering one language at a time needs.
     *
     * @throws IllegalArgumentException if {@code additions} is null, or if the result would have no usable
     *         slug -- see {@link #requireSlug}
     */
    static State merge(State current, Map<String, String> additions, String what) {
        if (additions == null) {
            throw new IllegalArgumentException("localizedNames must not be null when localizing " + what);
        }
        Map<String, String> names = decode(current.localizedNamesJson());
        for (Map.Entry<String, String> addition : additions.entrySet()) {
            if (addition.getKey() == null || addition.getKey().isBlank()) {
                throw new IllegalArgumentException(
                        "A localized name needs a locale, but " + what + " was given a null or blank one");
            }
            names.put(addition.getKey(), addition.getValue());
        }

        if (current.slug() != null && !current.slug().isBlank()) {
            // Rule 1: an existing slug is identity. Never re-derived, however the names change.
            return new State(encode(names), current.slug(), current.slugLocale());
        }

        SlugSource derived = deriveSlug(names);
        requireSlug(derived, names, what);
        return new State(encode(names), derived.slug(), derived.locale());
    }

    /** {@link #merge} from a JSON object of {@code {"locale": "display name"}}. */
    static State mergeJson(State current, String json, String what) {
        return merge(current, parse(json, what), what);
    }

    /**
     * The locale whose display string should become the slug, and the slug itself -- or an empty source when
     * no entry yields a usable one.
     */
    private static SlugSource deriveSlug(Map<String, String> names) {
        // Rule 2a: a plain "en" (in any case) beats a variety, so an author who supplied both gets the one
        // they most likely meant.
        SlugSource exactEnglish = firstUsable(names, locale -> ENGLISH.equals(normalizeLanguage(locale))
                && ENGLISH.equals(locale.trim().toLowerCase(Locale.ROOT)));
        if (exactEnglish != null) {
            return exactEnglish;
        }
        // Rule 2b: any English variety -- en-US, en_GB, EN-au.
        SlugSource anyEnglish = firstUsable(names, locale -> ENGLISH.equals(normalizeLanguage(locale)));
        if (anyEnglish != null) {
            return anyEnglish;
        }
        // Rule 3: first entry that yields anything usable, in iteration order.
        SlugSource anything = firstUsable(names, locale -> true);
        return anything != null ? anything : new SlugSource(null, null);
    }

    /** The first entry matching {@code localeFilter} whose display string slugifies to something usable. */
    private static SlugSource firstUsable(Map<String, String> names,
            java.util.function.Predicate<String> localeFilter) {
        for (Map.Entry<String, String> entry : names.entrySet()) {
            if (entry.getKey() == null || !localeFilter.test(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            String slug = Slugs.slugify(entry.getValue());
            if (!slug.isBlank()) {
                return new SlugSource(slug, entry.getKey());
            }
        }
        return null;
    }

    /**
     * The language subtag of a locale key, lowercased -- {@code "en-US"}, {@code "en_GB"} and {@code "EN"}
     * all normalize to {@code "en"}.
     *
     * <p>Hand-rolled rather than {@link Locale#forLanguageTag}, which accepts only the hyphen form and
     * silently yields an empty language for {@code "en_GB"} -- an underscore separator is common enough in
     * resource-bundle-derived keys that treating it as "not English" would be a trap.
     */
    private static String normalizeLanguage(String locale) {
        String trimmed = locale.trim();
        int separator = indexOfSeparator(trimmed);
        String language = separator < 0 ? trimmed : trimmed.substring(0, separator);
        return language.toLowerCase(Locale.ROOT);
    }

    private static int indexOfSeparator(String locale) {
        for (int i = 0; i < locale.length(); i++) {
            char c = locale.charAt(i);
            if (c == '-' || c == '_') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Refuses a Tag or TagSet that would have no slug.
     *
     * <p>The slug is the only {@code @Vectorize} field either type has, so without one the entity has an
     * absent vector: it cannot participate in tag-similarity search, cannot be found by the classifier, and
     * would sit in the store looking like a real tag. Failing here -- at the input that caused it -- rather
     * than at some later read is the whole point (OMI-201).
     */
    private static void requireSlug(SlugSource derived, Map<String, String> names, String what) {
        if (derived.slug() != null) {
            return;
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("Cannot create " + what
                    + " with no localized names: the slug is derived from them and is required.");
        }
        throw new IllegalArgumentException("Cannot derive a slug for " + what + " from any of "
                + names.keySet() + " -- every display string slugifies to nothing. The slug is this type's"
                + " only vectorized field, so without one it cannot be searched or classified. Supply a"
                + " Latin-script name (English by preference); this library does not transliterate.");
    }

    private record SlugSource(String slug, String locale) {
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

    /**
     * {@link #decode}, but for caller-supplied JSON: reports a syntax error as this library's own
     * {@link IllegalArgumentException} rather than leaking Gson's {@link JsonSyntaxException} out of an API
     * that says nothing about Gson.
     */
    static Map<String, String> parse(String json, String what) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                    "localizedNames JSON must not be null or blank when localizing " + what);
        }
        Map<String, String> decoded;
        try {
            decoded = GSON.fromJson(json, MAP_TYPE);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Not a JSON object of {\"locale\": \"display name\"} for "
                    + what + ": " + e.getMessage(), e);
        }
        if (decoded == null) {
            throw new IllegalArgumentException(
                    "localizedNames JSON decoded to nothing for " + what + ": " + json);
        }
        return decoded;
    }
}
