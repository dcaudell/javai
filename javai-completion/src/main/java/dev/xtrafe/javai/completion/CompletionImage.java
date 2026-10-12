package dev.xtrafe.javai.completion;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

/**
 * An image sent with a {@link CompletionRequest}, for a model that reads images (OMI-639): either its bytes or a URL
 * the provider fetches itself, and always its media type ({@code image/png}, {@code image/jpeg}, {@code image/gif},
 * {@code image/webp}).
 *
 * <p>An image is not text, so it takes no part in {@link CompletionRequest#render()} and is not counted against a
 * {@code PromptContext}'s character budget; what it costs is the provider's to say. A {@link Cortex} that cannot send
 * images throws rather than dropping them: a request answered without the image it was about is a wrong answer that
 * looks like a right one.
 *
 * @param data      the image's bytes, or {@code null} when {@code url} names it
 * @param mediaType the image's media type
 * @param url       where the provider fetches the image, or {@code null} when {@code data} holds it
 */
public record CompletionImage(byte[] data, String mediaType, URI url) {

    public CompletionImage {
        if ((data == null) == (url == null)) {
            throw new IllegalArgumentException("a CompletionImage is either bytes or a URL, exactly one of them");
        }
        if (mediaType == null || !mediaType.startsWith("image/")) {
            throw new IllegalArgumentException("a CompletionImage needs an image media type, e.g. image/png -- got "
                    + mediaType);
        }
        data = data == null ? null : data.clone();
    }

    /** An image sent as its bytes. */
    public static CompletionImage of(byte[] data, String mediaType) {
        return new CompletionImage(data, mediaType, null);
    }

    /** An image the provider fetches from {@code url}. */
    public static CompletionImage of(URI url, String mediaType) {
        return new CompletionImage(null, mediaType, url);
    }

    /** The image's bytes, copied; {@code null} for an image sent by URL. */
    @Override
    public byte[] data() {
        return data == null ? null : data.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CompletionImage other && Arrays.equals(data, other.data)
                && mediaType.equals(other.mediaType) && Objects.equals(url, other.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(data), mediaType, url);
    }

    @Override
    public String toString() {
        return "CompletionImage[" + mediaType + ", " + (url != null ? url : data.length + " bytes") + "]";
    }
}
