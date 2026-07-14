package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Explicitly excludes a field from the local embedding. Wins over {@code @Vectorize} if a field somehow
 * carries both -- an explicit exclude signal should never be silently overridden by an include one on the
 * same field. See doc/spec/vector-core.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface VectorizeIgnore {
}
