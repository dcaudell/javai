package dev.xtrafe.javai.completion;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * The HTTP a batch API takes (OMI-641): JSON requests, a multipart file upload, text responses. Hand-rolled, as
 * {@link CortexReplicate}'s client is, because Spring AI's chat models have no batch API to wrap.
 */
final class BatchHttp {

    private final HttpClient client = HttpClient.newHttpClient();
    private final Map<String, String> headers;

    BatchHttp(Map<String, String> headers) {
        this.headers = Map.copyOf(headers);
    }

    String get(String url) {
        return send(builder(url).GET().build());
    }

    String postJson(String url, String json) {
        return send(builder(url).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build());
    }

    /** A multipart/form-data upload of one file beside plain fields, as a files API takes it. */
    String postFile(String url, Map<String, String> fields, String fileName, String fileContent) {
        String boundary = "javai-" + UUID.randomUUID();
        StringBuilder body = new StringBuilder();
        fields.forEach((name, value) -> body.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
                .append("Content-Type: application/jsonl\r\n\r\n")
                .append(fileContent).append("\r\n")
                .append("--").append(boundary).append("--\r\n");
        return send(builder(url).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
    }

    private HttpRequest.Builder builder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        headers.forEach(builder::header);
        return builder;
    }

    private String send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CompletionException("Batch request to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Interrupted calling " + request.uri(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new CompletionException("Batch request to " + request.uri() + " returned HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
