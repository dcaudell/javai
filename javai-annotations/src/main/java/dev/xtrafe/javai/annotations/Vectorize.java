package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/** Includes a field in its declaring object's local embedding. See doc/spec/vector-core.md. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Vectorize {
}
