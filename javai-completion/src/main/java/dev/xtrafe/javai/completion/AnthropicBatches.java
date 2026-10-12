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
import org.springframework.ai.anthropic.AnthropicChatOptions;

/**
 * Anthropic's Message Batches API for {@link CortexAnthropic} (OMI-641). Each request's {@code params} are the Messages
 * API body {@link CortexAnthropic#complete} sends through Spring AI -- the same model, text, images, limits, thinking and
 * {@code output_config} schema, in the same wire shape -- so a request answers alike either way.
 */
final class AnthropicBatches {

    private final BatchHttp http;
    private final String baseUrl;
    private final String model;
    private final int contextWindowTokens;

    AnthropicBatches(String baseUrl, String apiKey, String model, int contextWindowTokens) {
        this.http = new BatchHttp(Map.of("x-api-key", apiKey == null ? "" : apiKey,
                "anthropic-version", "2023-06-01"));
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.contextWindowTokens = contextWindowTokens;
    }

    CompletionBatch submit(List<CompletionRequest> requests) {
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("a batch needs at least one request");
        }
        JsonArray items = new JsonArray();
        for (int i = 0; i < requests.size(); i++) {
            JsonObject item = new JsonObject();
            item.addProperty("custom_id", String.valueOf(i));
            item.add("params", params(requests.get(i), model, contextWindowTokens));
            items.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("requests", items);
        return new Batch(JsonParser.parseString(http.postJson(baseUrl + "/v1/messages/batches", body.toString()))
                .getAsJsonObject());
    }

    CompletionBatch find(String id) {
        return new Batch(read(id));
    }

    private JsonObject read(String id) {
        return JsonParser.parseString(http.get(baseUrl + "/v1/messages/batches/" + id)).getAsJsonObject();
    }

    /** The Messages API body for {@code request}, as Spring AI's Anthropic model sends it. */
    static JsonObject params(CompletionRequest request, String model, int contextWindowTokens) {
        JsonObject params = new JsonObject();
        params.addProperty("model", model);
        params.addProperty("max_tokens",
                request.maxTokens() != null ? request.maxTokens() : AnthropicChatOptions.DEFAULT_MAX_TOKENS);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        String text = request.render(contextWindowTokens);
        if (request.images().isEmpty()) {
            message.addProperty("content", text);
        } else {
            JsonArray content = new JsonArray();
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", text);
            content.add(textBlock);
            for (CompletionImage image : request.images()) {
                JsonObject source = new JsonObject();
                if (image.url() != null) {
                    source.addProperty("type", "url");
                    source.addProperty("url", image.url().toString());
                } else {
                    source.addProperty("type", "base64");
                    source.addProperty("media_type", image.mediaType());
                    source.addProperty("data", Base64.getEncoder().encodeToString(image.data()));
                }
                JsonObject block = new JsonObject();
                block.addProperty("type", "image");
                block.add("source", source);
                content.add(block);
            }
            message.add("content", content);
        }
        JsonArray messages = new JsonArray();
        messages.add(message);
        params.add("messages", messages);
        if (request.temperature() != null) {
            params.addProperty("temperature", request.temperature());
        }
        if (request.providerOptions().get("thinking_budget_tokens") instanceof Integer budget) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            thinking.addProperty("budget_tokens", budget);
            params.add("thinking", thinking);
        }
        if (request.responseSchema() != null) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_schema");
            format.add("schema", JsonParser.parseString(request.responseSchema()));
            JsonObject outputConfig = new JsonObject();
            outputConfig.add("format", format);
            params.add("output_config", outputConfig);
        }
        return params;
    }

    private final class Batch implements CompletionBatch {

        private final String id;
        private JsonObject state;

        Batch(JsonObject state) {
            this.id = state.get("id").getAsString();
            this.state = state;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Status status() {
            return switch (state.get("processing_status").getAsString()) {
                case "ended" -> Status.ENDED;
                case "canceling" -> Status.CANCELING;
                default -> Status.IN_PROGRESS;
            };
        }

        @Override
        public Status refresh() {
            state = read(id);
            return status();
        }

        @Override
        public void cancel() {
            state = JsonParser.parseString(http.postJson(baseUrl + "/v1/messages/batches/" + id + "/cancel", "{}"))
                    .getAsJsonObject();
        }

        @Override
        public List<Item> results() {
            if (status() != Status.ENDED) {
                throw new IllegalStateException("batch " + id + " has not ended: " + status());
            }
            Map<Integer, Item> byIndex = new TreeMap<>();
            for (String line : http.get(state.get("results_url").getAsString()).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
                int index = Integer.parseInt(entry.get("custom_id").getAsString());
                byIndex.put(index, item(index, entry.getAsJsonObject("result")));
            }
            int total = 0;
            for (Map.Entry<String, JsonElement> count : state.getAsJsonObject("request_counts").entrySet()) {
                total += count.getValue().getAsInt();
            }
            List<Item> items = new ArrayList<>(total);
            for (int i = 0; i < Math.max(total, byIndex.size()); i++) {
                items.add(byIndex.getOrDefault(i, new Item(i, null, "no result returned")));
            }
            return items;
        }

        private Item item(int index, JsonObject result) {
            String type = result.get("type").getAsString();
            if (!"succeeded".equals(type)) {
                JsonObject error = result.has("error") ? result.getAsJsonObject("error") : null;
                String message = error != null && error.has("error")
                        ? error.getAsJsonObject("error").get("message").getAsString() : type;
                return new Item(index, null, message);
            }
            JsonObject message = result.getAsJsonObject("message");
            StringBuilder text = new StringBuilder();
            for (JsonElement block : message.getAsJsonArray("content")) {
                JsonObject b = block.getAsJsonObject();
                if ("text".equals(b.get("type").getAsString())) {
                    text.append(b.get("text").getAsString());
                }
            }
            JsonObject usage = message.getAsJsonObject("usage");
            return new Item(index, new CompletionResult(text.toString(), "anthropic", model, Instant.now(),
                    usage == null ? null : usage.get("input_tokens").getAsInt(),
                    usage == null ? null : usage.get("output_tokens").getAsInt()), null);
        }
    }
}
