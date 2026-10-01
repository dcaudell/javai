package dev.xtrafe.javai.completion;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The text result of a {@link Cortex#complete(CompletionRequest)} call, plus enough metadata to be
 * self-explanatory without cross-referencing which Cortex produced it.
 *
 * <p><b>Typed results (OMI-68).</b> {@link #as}, {@link #asList} and {@link #asSet} find the JSON in
 * {@link #text()} and unmarshal it with Gson; {@link #completion()} is the prose around it. Each holds the
 * JSON to the destination type: every field present (a non-primitive one may be {@code null}), extra keys
 * ignored, values of the declared JSON type. Pair them with {@link CompletionRequest.Builder#responseType}
 * and its siblings, which make a schema-capable Cortex return exactly that shape. Outcomes:
 * <ul>
 *   <li>no JSON in the text, or a {@code null} value under {@code responseOptional} -- {@link Optional#empty()};</li>
 *   <li>JSON that will not parse, more than one JSON value, a value of the wrong shape, or a duplicate in a
 *       {@code Set} -- a {@link CompletionException} saying which.</li>
 * </ul>
 * A schema-bound reply arrives as {@code {"completion": ..., "response": ...}}; that envelope is unwrapped
 * here, so the same calls read it and a bare value alike.
 */
public record CompletionResult(String text, String providerId, String modelId, Instant completedAt) {

    private static final Gson GSON = new Gson();

    public CompletionResult {
        if (text == null) {
            throw new IllegalArgumentException("CompletionResult text must not be null");
        }
        if (providerId == null) {
            throw new IllegalArgumentException("CompletionResult providerId must not be null");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("CompletionResult modelId must not be null");
        }
        if (completedAt == null) {
            completedAt = Instant.now();
        }
    }

    /** The one JSON object in the text, as a {@code type}. See this record's javadoc for every outcome. */
    public <T> Optional<T> as(Class<T> type) {
        ResponseSchemas.requireObjectType(type);
        Optional<JsonElement> value = typedValue(!ResponseSchemas.declares(type, ResponseSchemas.RESPONSE));
        value.ifPresent(element -> ResponseSchemas.validate(element, type, "response"));
        return value.map(element -> GSON.fromJson(element, type));
    }

    /** The one JSON array in the text, as a list of {@code type}, in order. */
    public <T> Optional<List<T>> asList(Class<T> type) {
        return elements(type).map(Collections::unmodifiableList);
    }

    /** The one JSON array in the text, as a set of {@code type} -- a duplicate, by {@code equals}, throws. */
    public <T> Optional<Set<T>> asSet(Class<T> type) {
        return elements(type).map(list -> {
            Set<T> set = new LinkedHashSet<>(list);
            if (set.size() != list.size()) {
                throw new CompletionException("The response holds " + (list.size() - set.size())
                        + " duplicate " + type.getSimpleName() + " value(s) where a Set was asked for");
            }
            return Collections.unmodifiableSet(set);
        });
    }

    /**
     * The prose part: the envelope's {@code completion} field when the reply has one, otherwise the text with
     * every JSON value found in it removed. Never throws -- JSON that would not parse is left in place.
     */
    public String completion() {
        List<ResponseJson.Span> spans = ResponseJson.find(text);
        if (spans.size() == 1 && spans.get(0).value() instanceof JsonObject object && isEnvelope(object, true)
                && object.get(ResponseSchemas.COMPLETION) instanceof JsonElement completion
                && completion.isJsonPrimitive()) {
            return completion.getAsString();
        }
        StringBuilder prose = new StringBuilder();
        int from = 0;
        for (ResponseJson.Span span : spans) {
            if (span.error() == null) {
                prose.append(text, from, span.start());
                from = span.end();
            }
        }
        return prose.append(text.substring(from)).toString().strip();
    }

    private <T> Optional<List<T>> elements(Class<T> type) {
        ResponseSchemas.requireObjectType(type);
        Optional<JsonElement> value = typedValue(true);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (!(value.get() instanceof JsonArray array)) {
            throw new CompletionException("The response should be an array of " + type.getSimpleName()
                    + " but is " + value.get());
        }
        ResponseSchemas.validate(array, TypeToken.getParameterized(List.class, type).getType(), "response");
        List<T> list = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            list.add(GSON.fromJson(element, type));
        }
        return Optional.of(list);
    }

    /** The single JSON value in the text, unwrapped from its envelope; empty for none or a {@code null}. */
    private Optional<JsonElement> typedValue(boolean envelopeAllowed) {
        List<ResponseJson.Span> spans = ResponseJson.find(text);
        for (ResponseJson.Span span : spans) {
            if (span.error() != null) {
                throw new CompletionException("The response holds JSON that does not parse: " + span.error());
            }
        }
        if (spans.isEmpty()) {
            return Optional.empty();
        }
        if (spans.size() > 1) {
            throw new CompletionException("The response holds " + spans.size()
                    + " separate JSON values where one was asked for");
        }
        JsonElement value = spans.get(0).value();
        if (value instanceof JsonObject object && isEnvelope(object, envelopeAllowed)) {
            value = object.get(ResponseSchemas.RESPONSE);
        }
        return value.isJsonNull() ? Optional.empty() : Optional.of(value);
    }

    /** {@code {"response": ...}}, optionally with {@code "completion"}, and nothing else. */
    private static boolean isEnvelope(JsonObject object, boolean allowed) {
        return allowed && object.has(ResponseSchemas.RESPONSE) && object.keySet().stream()
                .allMatch(key -> key.equals(ResponseSchemas.RESPONSE) || key.equals(ResponseSchemas.COMPLETION));
    }
}
