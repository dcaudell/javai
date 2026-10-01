package dev.xtrafe.javai.completion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OMI-68: every outcome {@link CompletionResult#as} and its siblings define, and the schema behind them. */
class CompletionResultTypedTest {

    record Verdict(boolean personA, boolean personB) {
    }

    enum Mood { CALM, @SerializedName("on-edge") ON_EDGE }

    record Reading(String label, Integer score, Mood mood, List<String> tags, UUID id) {
    }

    record Wrapper(@SerializedName("verdict_text") String text, Verdict verdict) {
    }

    record HasResponseField(String response) {
    }

    record Recursive(Recursive next) {
    }

    record WithMap(Map<String, String> values) {
    }

    private static CompletionResult result(String text) {
        return new CompletionResult(text, "test", "test-model", null);
    }

    // ---- finding the JSON ---------------------------------------------------------------------------

    @Test
    void noJsonIsEmpty() {
        assertEquals(Optional.empty(), result("I could not decide.").as(Verdict.class));
        assertEquals(Optional.empty(), result("").asList(Verdict.class));
    }

    @Test
    void jsonSurroundedByProseIsRead() {
        Verdict verdict = result("Having read it all: {\"personA\": true, \"personB\": false}. Done.")
                .as(Verdict.class).orElseThrow();
        assertEquals(new Verdict(true, false), verdict);
    }

    @Test
    void aFencedBlockIsReadAndOtherBracesAroundItAreProse() {
        String text = "Thinking {about it} first.\n```json\n{\"personA\": false, \"personB\": true}\n```\nThanks.";
        assertEquals(new Verdict(false, true), result(text).as(Verdict.class).orElseThrow());
    }

    @Test
    void malformedJsonThrows() {
        CompletionException e = assertThrows(CompletionException.class,
                () -> result("Answer: {personA: true, 'personB': false}").as(Verdict.class));
        assertTrue(e.getMessage().contains("does not parse"), e.getMessage());
    }

    @Test
    void moreThanOneJsonObjectThrowsWhereOneWasAskedFor() {
        CompletionException e = assertThrows(CompletionException.class, () -> result(
                "{\"personA\": true, \"personB\": true} or maybe {\"personA\": false, \"personB\": false}")
                .as(Verdict.class));
        assertTrue(e.getMessage().contains("2 separate JSON values"), e.getMessage());
    }

    // ---- holding it to the type ---------------------------------------------------------------------

    @Test
    void aMissingFieldThrowsAndAnExtraOneIsIgnored() {
        CompletionException e = assertThrows(CompletionException.class,
                () -> result("{\"personA\": true}").as(Verdict.class));
        assertTrue(e.getMessage().contains("missing field response.personB"), e.getMessage());
        assertEquals(new Verdict(true, true),
                result("{\"personA\": true, \"personB\": true, \"why\": \"x\"}").as(Verdict.class).orElseThrow());
    }

    @Test
    void aNullFieldIsNullUnlessItIsAPrimitive() {
        Reading reading = result("{\"label\": null, \"score\": null, \"mood\": null, \"tags\": null, \"id\": null}")
                .as(Reading.class).orElseThrow();
        assertNull(reading.label());
        assertNull(reading.score());
        assertThrows(CompletionException.class,
                () -> result("{\"personA\": null, \"personB\": true}").as(Verdict.class));
    }

    @Test
    void valuesMustHaveTheDeclaredJsonTypeNotOneGsonWouldCoerce() {
        assertThrows(CompletionException.class,
                () -> result("{\"personA\": \"true\", \"personB\": true}").as(Verdict.class));
        String base = "{\"label\": \"x\", \"mood\": \"CALM\", \"tags\": [], \"id\": null, \"score\": %s}";
        assertThrows(CompletionException.class, () -> result(base.formatted("\"7\"")).as(Reading.class));
        assertThrows(CompletionException.class, () -> result(base.formatted("7.5")).as(Reading.class));
        assertEquals(7, result(base.formatted("7")).as(Reading.class).orElseThrow().score());
    }

    @Test
    void enumsUuidsAndSerializedNamesFollowGson() {
        UUID id = UUID.randomUUID();
        Reading reading = result("{\"label\": \"x\", \"score\": 1, \"mood\": \"on-edge\", \"tags\": [\"a\"], "
                + "\"id\": \"" + id + "\"}").as(Reading.class).orElseThrow();
        assertEquals(Mood.ON_EDGE, reading.mood());
        assertEquals(id, reading.id());
        assertThrows(CompletionException.class, () -> result("{\"label\": \"x\", \"score\": 1, "
                + "\"mood\": \"ON_EDGE\", \"tags\": [], \"id\": null}").as(Reading.class));
        assertThrows(CompletionException.class, () -> result("{\"label\": \"x\", \"score\": 1, "
                + "\"mood\": \"CALM\", \"tags\": [], \"id\": \"not-a-uuid\"}").as(Reading.class));
        Wrapper wrapper = result("{\"verdict_text\": \"ok\", \"verdict\": {\"personA\": true, \"personB\": false}}")
                .as(Wrapper.class).orElseThrow();
        assertEquals("ok", wrapper.text());
    }

    @Test
    void asWantsAnObjectAndRefusesTypesWithNoFixedShape() {
        assertThrows(CompletionException.class, () -> result("[{\"personA\": true, \"personB\": true}]")
                .as(Verdict.class));
        assertThrows(IllegalArgumentException.class, () -> result("{}").as(String.class));
        assertThrows(IllegalArgumentException.class, () -> result("{}").as(Recursive.class));
        assertThrows(IllegalArgumentException.class, () -> result("{}").as(WithMap.class));
    }

    // ---- collections --------------------------------------------------------------------------------

    @Test
    void aListIsReadFromABareArrayOrTheEnvelopeInOrder() {
        String array = "[{\"personA\": true, \"personB\": false}, {\"personA\": true, \"personB\": false}]";
        assertEquals(List.of(new Verdict(true, false), new Verdict(true, false)),
                result(array).asList(Verdict.class).orElseThrow());
        assertEquals(2, result("{\"response\": " + array + "}").asList(Verdict.class).orElseThrow().size());
    }

    @Test
    void aSetRefusesDuplicates() {
        String distinct = "[{\"personA\": true, \"personB\": false}, {\"personA\": false, \"personB\": false}]";
        assertEquals(Set.of(new Verdict(true, false), new Verdict(false, false)),
                result(distinct).asSet(Verdict.class).orElseThrow());
        CompletionException e = assertThrows(CompletionException.class, () -> result(
                "[{\"personA\": true, \"personB\": false}, {\"personA\": true, \"personB\": false}]")
                .asSet(Verdict.class));
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    void aCollectionHoldsItsElementTypeToo() {
        assertThrows(CompletionException.class, () -> result("{\"personA\": true, \"personB\": false}")
                .asList(Verdict.class));
        assertThrows(CompletionException.class, () -> result("[{\"personA\": true}]").asList(Verdict.class));
        assertThrows(CompletionException.class, () -> result("[null]").asList(Verdict.class));
    }

    // ---- the envelope and the prose ----------------------------------------------------------------

    @Test
    void theEnvelopeIsUnwrappedAndANullResponseIsEmpty() {
        CompletionResult answered = result("{\"completion\": \"Both agreed.\", "
                + "\"response\": {\"personA\": true, \"personB\": true}}");
        assertEquals(new Verdict(true, true), answered.as(Verdict.class).orElseThrow());
        assertEquals("Both agreed.", answered.completion());
        assertEquals(Optional.empty(), result("{\"response\": null}").as(Verdict.class));
    }

    @Test
    void aTypeWithItsOwnResponseFieldIsNotMistakenForTheEnvelope() {
        assertEquals("hi", result("{\"response\": \"hi\"}").as(HasResponseField.class).orElseThrow().response());
    }

    @Test
    void completionIsTheProseAroundTheJsonWhenThereIsNoEnvelope() {
        assertEquals("Reasoning first.\n\nThat is all.", result(
                "Reasoning first.\n```json\n{\"personA\": true, \"personB\": true}\n```\nThat is all.")
                .completion());
        assertEquals("No JSON {here} at all.", result("No JSON {here} at all.").completion());
    }

    // ---- the request side --------------------------------------------------------------------------

    @Test
    void theRequestSchemaIsAStrictEnvelopeDerivedFromTheType() {
        JsonObject schema = schema(CompletionRequest.builder().prompt("p").responseType(Verdict.class)
                .withCompletion().build());
        assertEquals("object", schema.get("type").getAsString());
        assertEquals(false, schema.get("additionalProperties").getAsBoolean());
        assertEquals("[\"completion\",\"response\"]", schema.get("required").toString());
        JsonObject verdict = schema.getAsJsonObject("properties").getAsJsonObject("response");
        assertEquals("[\"personA\",\"personB\"]", verdict.get("required").toString());
        assertEquals("boolean", verdict.getAsJsonObject("properties").getAsJsonObject("personA")
                .get("type").getAsString());
    }

    @Test
    void optionalCollectionAndNullableFieldsAreExpressedInTheSchema() {
        JsonObject optional = schema(CompletionRequest.builder().prompt("p").responseOptional(Verdict.class).build());
        assertEquals("null", optional.getAsJsonObject("properties").getAsJsonObject("response")
                .getAsJsonArray("anyOf").get(1).getAsJsonObject().get("type").getAsString());
        JsonObject list = schema(CompletionRequest.builder().prompt("p").responseSetOf(Reading.class).build());
        JsonObject reading = list.getAsJsonObject("properties").getAsJsonObject("response").getAsJsonObject("items");
        JsonObject mood = reading.getAsJsonObject("properties").getAsJsonObject("mood");
        assertEquals("[\"CALM\",\"on-edge\"]", mood.getAsJsonArray("anyOf").get(0).getAsJsonObject()
                .get("enum").toString(), "a reference field is nullable, and enums use Gson's names");
        assertNull(list.getAsJsonObject("properties").get("completion"));
    }

    @Test
    void theRequestRefusesWhatItCannotDescribe() {
        assertNull(CompletionRequest.builder().prompt("p").build().responseSchema());
        assertThrows(IllegalArgumentException.class,
                () -> CompletionRequest.builder().responseType(WithMap.class));
        assertThrows(IllegalStateException.class,
                () -> CompletionRequest.builder().prompt("p").withCompletion().build());
    }

    private static JsonObject schema(CompletionRequest request) {
        return JsonParser.parseString(request.responseSchema()).getAsJsonObject();
    }
}
