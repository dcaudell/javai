package dev.xtrafe.javai.completion;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the JSON in a completion's text (OMI-68), in the first of three places that has any:
 * <ol>
 *   <li>{@code ```json} fenced blocks -- each one a value, whatever else the text says;</li>
 *   <li>the whole text, when it is one JSON object or array -- what a schema-bound reply is;</li>
 *   <li>every outermost {@code {...}} in the surrounding prose. A bare array is recognized only by the two
 *       rules above, since {@code [1]} in ordinary prose is valid JSON too.</li>
 * </ol>
 * Parsing is strict: Gson's lenient defaults would read single quotes and unquoted keys as JSON.
 */
final class ResponseJson {

    private static final Pattern FENCED = Pattern.compile("```json[ \\t]*\\R(.*?)```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new Gson();

    /** One place JSON was found: the span of text it occupies, and its value or why it would not parse. */
    record Span(int start, int end, JsonElement value, String error) {
    }

    private ResponseJson() {
    }

    static List<Span> find(String text) {
        List<Span> fenced = new ArrayList<>();
        Matcher matcher = FENCED.matcher(text);
        while (matcher.find()) {
            fenced.add(span(matcher.start(), matcher.end(), matcher.group(1)));
        }
        if (!fenced.isEmpty()) {
            return fenced;
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            Span whole = span(0, text.length(), trimmed);
            if (whole.error() == null) {
                return List.of(whole);
            }
        }
        return outermostObjects(text);
    }

    private static List<Span> outermostObjects(String text) {
        List<Span> spans = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (depth == 0) {
                if (c == '{') {
                    start = i;
                    depth = 1;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                spans.add(span(start, i + 1, text.substring(start, i + 1)));
            }
        }
        return spans;
    }

    private static Span span(int start, int end, String json) {
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = GSON.getAdapter(JsonElement.class).read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return new Span(start, end, null, "trailing content after the JSON value");
            }
            return new Span(start, end, value, null);
        } catch (IOException | RuntimeException malformed) {
            return new Span(start, end, null, malformed.getMessage());
        }
    }
}
