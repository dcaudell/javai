package dev.xtrafe.javai.completion;

/**
 * A completion that arrived but does not hold the typed value it was asked for (OMI-640): why, as a {@link Reason},
 * beside the message. Thrown by {@link CompletionResult#as} and its siblings, and by
 * {@link Cortex#completeTyped} once its repair attempt, if any, has failed too.
 */
public class CompletionResponseException extends CompletionException {

    /** Why a completion's text is not the value asked for. */
    public enum Reason {
        /** No JSON value at all, where one was required. */
        NO_VALUE,
        /** JSON that does not parse, or more than one JSON value where one was asked for. */
        MALFORMED,
        /** A JSON value of the wrong shape: a missing field, a wrong type, an enum outside its values. */
        SCHEMA_VIOLATION
    }

    private final Reason reason;
    private final String text;

    public CompletionResponseException(Reason reason, String message) {
        this(reason, message, null, null);
    }

    public CompletionResponseException(Reason reason, String message, String text, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.text = text;
    }

    public Reason reason() {
        return reason;
    }

    /** The completion's text, when known: what the model actually said. */
    public String text() {
        return text;
    }

    /** This failure, now knowing the text it was about. */
    CompletionResponseException about(String text) {
        return this.text != null ? this : new CompletionResponseException(reason, getMessage(), text, getCause());
    }
}
