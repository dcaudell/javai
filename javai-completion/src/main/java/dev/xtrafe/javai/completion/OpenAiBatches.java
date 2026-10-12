package dev.xtrafe.javai.completion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * OpenAI's Batch API for {@link CortexOpenAI} (OMI-641): the requests are uploaded as a JSONL file of chat-completions
 * bodies, then batched. Each body is the one {@link CortexOpenAI#complete} sends through Spring AI -- the same model,
 * text, images, limits, reasoning effort and strict {@code json_schema} response format, in the same wire shape. The
 * files and batches endpoints sit beside chat completions under the configured base URL, as the chat endpoint does.
 */
final class OpenAiBatches {

    private static final String CHAT_COMPLETIONS = "/v1/chat/completions";

    private final BatchHttp http;
    private final String baseUrl;
    private final String model;
    private final int contextWindowTokens;

    OpenAiBatches(String baseUrl, String apiKey, String model, int contextWindowTokens) {
        this.http = new BatchHttp(Map.of("Authorization", "Bearer " + (apiKey == null ? "" : apiKey)));
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.contextWindowTokens = contextWindowTokens;
    }

    CompletionBatch submit(List<CompletionRequest> requests) {
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("a batch needs at least one request");
        }
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i < requests.size(); i++) {
            JsonObject line = new JsonObject();
            line.addProperty("custom_id", String.valueOf(i));
            line.addProperty("method", "POST");
            line.addProperty("url", CHAT_COMPLETIONS);
            line.add("body", body(requests.get(i), model, contextWindowTokens));
            jsonl.append(line).append('\n');
        }
        String fileId = JsonParser.parseString(http.postFile(baseUrl + "/files", Map.of("purpose", "batch"),
                "batch.jsonl", jsonl.toString())).getAsJsonObject().get("id").getAsString();
        JsonObject create = new JsonObject();
        create.addProperty("input_file_id", fileId);
        create.addProperty("endpoint", CHAT_COMPLETIONS);
        create.addProperty("completion_window", "24h");
        return new Batch(JsonParser.parseString(http.postJson(baseUrl + "/batches", create.toString()))
                .getAsJsonObject(), requests.size());
    }

    CompletionBatch find(String id) {
        return new Batch(read(id), -1);
    }

    private JsonObject read(String id) {
        return JsonParser.parseString(http.get(baseUrl + "/batches/" + id)).getAsJsonObject();
    }

    /** The chat-completions body for {@code request}, as Spring AI's OpenAI model sends it. */
    static JsonObject body(CompletionRequest request, String model, int contextWindowTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        String text = request.render(contextWindowTokens);
        if (request.images().isEmpty()) {
            message.addProperty("content", text);
        } else {
            JsonArray content = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", text);
            content.add(textPart);
            for (CompletionImage image : request.images()) {
                JsonObject url = new JsonObject();
                url.addProperty("url", image.url() != null ? image.url().toString()
                        : "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.data()));
                JsonObject part = new JsonObject();
                part.addProperty("type", "image_url");
                part.add("image_url", url);
                content.add(part);
            }
            message.add("content", content);
        }
        JsonArray messages = new JsonArray();
        messages.add(message);
        body.add("messages", messages);
        if (request.maxTokens() != null) {
            body.addProperty("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            body.addProperty("temperature", request.temperature());
        }
        if (request.providerOptions().get("reasoning_effort") instanceof String effort) {
            body.addProperty("reasoning_effort", effort);
        }
        if (request.responseSchema() != null) {
            JsonObject schema = new JsonObject();
            schema.addProperty("name", "json_schema");
            schema.add("schema", JsonParser.parseString(request.responseSchema()));
            schema.addProperty("strict", true);
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_schema");
            format.add("json_schema", schema);
            body.add("response_format", format);
        }
        return body;
    }

    private final class Batch implements CompletionBatch {

        private final String id;
        private final int submitted;
        private JsonObject state;

        Batch(JsonObject state, int submitted) {
            this.id = state.get("id").getAsString();
            this.state = state;
            this.submitted = submitted;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Status status() {
            return switch (state.get("status").getAsString()) {
                case "completed", "failed", "expired", "cancelled" -> Status.ENDED;
                case "cancelling" -> Status.CANCELING;
                default -> Status.IN_PROGRESS; // validating, in_progress, finalizing
            };
        }

        @Override
        public Status refresh() {
            state = read(id);
            return status();
        }

        @Override
        public void cancel() {
            state = JsonParser.parseString(http.postJson(baseUrl + "/batches/" + id + "/cancel", "{}"))
                    .getAsJsonObject();
        }

        @Override
        public List<Item> results() {
            if (status() != Status.ENDED) {
                throw new IllegalStateException("batch " + id + " has not ended: " + status());
            }
            Map<Integer, Item> byIndex = new TreeMap<>();
            for (String fileField : List.of("output_file_id", "error_file_id")) {
                JsonElement fileId = state.get(fileField);
                if (fileId == null || fileId.isJsonNull()) {
                    continue;
                }
                for (String line : http.get(baseUrl + "/files/" + fileId.getAsString() + "/content").split("\n")) {
                    if (!line.isBlank()) {
                        JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
                        int index = Integer.parseInt(entry.get("custom_id").getAsString());
                        byIndex.put(index, item(index, entry));
                    }
                }
            }
            int total = submitted >= 0 ? submitted : totalOf(state);
            String missing = "failed".equals(state.get("status").getAsString()) ? batchError(state) : "no result returned";
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < Math.max(total, byIndex.size()); i++) {
                items.add(byIndex.getOrDefault(i, new Item(i, null, missing)));
            }
            return items;
        }

        private Item item(int index, JsonObject entry) {
            JsonElement error = entry.get("error");
            JsonObject response = entry.has("response") && !entry.get("response").isJsonNull()
                    ? entry.getAsJsonObject("response") : null;
            if ((error != null && !error.isJsonNull()) || response == null
                    || response.get("status_code").getAsInt() / 100 != 2) {
                String message = error != null && !error.isJsonNull() && error.getAsJsonObject().has("message")
                        ? error.getAsJsonObject().get("message").getAsString()
                        : response == null ? "no response" : "HTTP " + response.get("status_code").getAsInt();
                return new Item(index, null, message);
            }
            JsonObject completion = response.getAsJsonObject("body");
            String text = completion.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            JsonObject usage = completion.getAsJsonObject("usage");
            return new Item(index, new CompletionResult(text, "openai", model, Instant.now(),
                    usage == null ? null : usage.get("prompt_tokens").getAsInt(),
                    usage == null ? null : usage.get("completion_tokens").getAsInt()), null);
        }

        private static int totalOf(JsonObject state) {
            JsonObject counts = state.getAsJsonObject("request_counts");
            return counts == null ? 0 : counts.get("total").getAsInt();
        }

        private static String batchError(JsonObject state) {
            JsonElement errors = state.get("errors");
            if (errors != null && errors.isJsonObject() && errors.getAsJsonObject().has("data")) {
                JsonArray data = errors.getAsJsonObject().getAsJsonArray("data");
                if (!data.isEmpty()) {
                    return "batch failed: " + data.get(0).getAsJsonObject().get("message").getAsString();
                }
            }
            return "batch failed";
        }
    }
}
