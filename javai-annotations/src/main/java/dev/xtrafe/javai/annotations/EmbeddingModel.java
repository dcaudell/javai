package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/** Overrides which embedding model vectorizes this element. See doc/spec/vector-core.md. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface EmbeddingModel {

    String value();
}
