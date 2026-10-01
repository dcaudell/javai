package dev.xtrafe.javai.substrate.fixtures;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;

/**
 * Carries two {@code @ExternalVector}s over one key field, alongside an ordinary {@code @Vectorize} field.
 *
 * <p>Two of them because that is the case the {@code @Repeatable} container exists for, and because the
 * weaver reads the two shapes by different paths (a single annotation sits directly on the type, two or
 * more sit inside a generated container) -- a fixture with only one would leave half the reading untested.
 */
@JavAIVectorizable
@ExternalVector(name = "pixels", keyField = "contentKey", model = "fake-image-model/pp1")
@ExternalVector(name = "audio", keyField = "contentKey", model = "fake-audio-model/pp1")
public class ExternalVectorWidget {

    @Vectorize
    private String caption;

    private String contentKey;

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public String getContentKey() {
        return contentKey;
    }

    public void setContentKey(String contentKey) {
        this.contentKey = contentKey;
    }
}
