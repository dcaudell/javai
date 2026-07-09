package dev.xtrafe.javai.completion;

/** Unchecked wrapper for any failure a {@link Cortex} implementation hits talking to its backend --
 *  a network failure, a non-2xx response, a malformed response body, or a streaming error. */
public class CompletionException extends RuntimeException {

    public CompletionException(String message) {
        super(message);
    }

    public CompletionException(String message, Throwable cause) {
        super(message, cause);
    }
}
