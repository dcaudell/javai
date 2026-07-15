package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Declares tagging participation -- same name as the {@code Taggable} marker interface (in
 * {@code javai-tagging}) it's paired with, same shape as {@link JavAIGraphNode}. Unwoven: there is no
 * per-instance state to intercept a setter around, since tagging state lives in persisted association rows,
 * not object fields. Independent of {@link JavAIVectorizable}/{@link JavAIGraphNode} -- a class can carry
 * any subset of the three. See doc/spec/tagging.md's "Orthogonality: Taggable is not Vectorizable".
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Taggable {
}
