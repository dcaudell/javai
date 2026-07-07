package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Declares knowledge-graph node participation -- same name as the {@code JavAIGraphNode}
 * interface (in {@code javai-collections}) it causes to be implemented. See
 * doc/spec/vector-collections.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface JavAIGraphNode {
}
