package dev.xtrafe.javai.substrate.fixtures;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Vectorize;

/**
 * Names an external vector after a {@code @Vectorize} field on the same class -- which must be refused at
 * weave time.
 *
 * <p>They share one cache-slot namespace, so weaving this would give the class two vectors writing to one
 * slot: one computed from the caption's text, one supplied from outside, each silently overwriting the
 * other depending on which was touched last. The same class of failure the reserved-accessor-name check
 * already guards, one level down.
 */
@JavAIVectorizable
@ExternalVector(name = "caption", keyField = "contentKey", model = "fake-image-model/pp1")
public class CollidingExternalVectorWidget {

    @Vectorize
    private String caption;

    private String contentKey;

    public void setCaption(String caption) {
        this.caption = caption;
    }
}
