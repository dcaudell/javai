package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/** Explicitly excludes a field from the local embedding. See doc/spec/vector-core.md. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface VectorizeIgnore {
}
