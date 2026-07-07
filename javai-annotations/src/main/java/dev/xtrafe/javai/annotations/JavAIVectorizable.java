package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Opts a class into the {@code JavAIVectorizable} interface (defined in {@code javai-runtime}).
 * The weaver in {@code javai-agent} implements every method of that interface on the annotated
 * class -- never write {@code implements JavAIVectorizable} by hand. See doc/spec/vector-core.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JavAIVectorizable {
}
