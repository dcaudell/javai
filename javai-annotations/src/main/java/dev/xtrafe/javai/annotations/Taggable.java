package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Declares tagging participation -- same name as the {@code Taggable} marker interface (in
 * {@code javai-tagging}) it's paired with, same shape as {@link JavAIGraphNode}. Unwoven: there is no
 * per-instance state to intercept a setter around, since tagging state lives in persisted association rows,
 * not object fields. Independent of {@link JavAIVectorizable}/{@link JavAIGraphNode} -- a class can carry
 * any subset of the three. See doc/spec/tagging.md's "Orthogonality: Taggable is not Vectorizable".
 *
 * <p><b>{@code @Inherited}, unlike {@link JavAIVectorizable}</b> (OMI-290). A subclass of a taggable class is
 * genuinely taggable: tagging state lives in association rows keyed by type name and id, and a subclass has
 * both -- so there is nothing a subclass could be missing. {@code @JavAIVectorizable} cannot say the same,
 * because it commits the weaver to synthesizing per-class bytecode, and inheriting the declaration would
 * promise a contract on classes the weaver never transformed. The practical effect is that a
 * {@code @MappedSuperclass} (or any shared base) declares the intent once for a whole hierarchy.
 *
 * <p>⚠️ Note that inheritance through an <em>interface</em> is a separate matter, and this annotation has no
 * bearing on it: {@code @Inherited} applies only to superclasses. A domain whose common supertype is an
 * interface expresses participation by having that interface extend the {@code Taggable} <em>marker
 * interface</em> instead, which every implementor then satisfies for free -- and which is all the tagging
 * runtime actually requires, since nothing reads this annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface Taggable {
}
