package dev.xtrafe.javai.completion;

import java.util.List;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;

/**
 * Between {@link CompletionRequest}/{@link CompletionResult} and Spring AI. The Spring AI {@link Prompt} a request becomes, for the Cortices built on Spring AI's chat models
 * ({@link CortexAnthropic}, and the OpenAI-compatible family through {@link CortexOpenAiCompatibleSupport}): one user
 * message of the rendered text, carrying the request's {@link CompletionRequest#images() images} as media (OMI-639).
 */
final class SpringAiPrompts {

    private SpringAiPrompts() {
    }

    static Prompt of(CompletionRequest request, int contextWindowTokens, ChatOptions options) {
        if (!request.tools().isEmpty() && !(options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions
                tooling && tooling.getToolCallbacks() != null && !tooling.getToolCallbacks().isEmpty())) {
            throw new IllegalArgumentException("this request offers tools -- answer it with "
                    + "complete(request, executor), which runs the calls the model makes");
        }
        String text = request.render(contextWindowTokens);
        if (request.images().isEmpty()) {
            return new Prompt(text, options);
        }
        UserMessage message = UserMessage.builder()
                .text(text)
                .media(request.images().stream().map(SpringAiPrompts::media).toList())
                .build();
        return new Prompt(List.of(message), options);
    }

    /** The completion a Spring AI {@link ChatResponse} carries, with the provider's token counts (OMI-641). */
    static CompletionResult result(ChatResponse response, String providerId, String model) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new CompletionResult(response.getResult().getOutput().getText(), providerId, model,
                java.time.Instant.now(), usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }

    private static Media media(CompletionImage image) {
        MimeType type = MimeType.valueOf(image.mediaType());
        return image.url() != null
                ? new Media(type, image.url())
                : new Media(type, new ByteArrayResource(image.data()));
    }
}
