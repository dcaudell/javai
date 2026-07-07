/**
 * Acceleration Substrate: the ByteBuddy-based weaver that implements the
 * {@code JavAIVectorizable}/{@code JavAIGraphNode} interfaces (defined in
 * {@code javai-runtime}/{@code javai-collections}) on any class carrying the
 * corresponding annotation from {@code javai-annotations}, and rewrites mutating
 * setters to call the runtime's {@code markDirty()}/{@code propagateDirty()} hooks.
 *
 * <p>{@link dev.xtrafe.javai.agent.JavAIWeaver} is the minimal spike proving this mechanism, per
 * doc/spec/acceleration-substrate.md and CLAUDE.md's build order: load-time weaving of a single
 * {@code @Vectorize} field's conventional setter, correct {@code FieldDirty} marking, and lazy
 * {@code vector()} recomputation on next read -- see
 * {@link dev.xtrafe.javai.agent.WeaverRuntimeSupport} and
 * {@link dev.xtrafe.javai.agent.MarkDirtyAdvice} for the mechanics, and
 * {@code VectorizationWeavingSpikeTest} for the lifecycle proof. Deliberately out of scope here:
 * {@code dependents()}/back-edge propagation across a graph (only proven for a single object's own
 * {@code FieldDirty} cycle, not {@code SummaryDirty}), non-conventional setters, and multiple
 * {@code @Vectorize} fields per class -- those belong to the full {@code javai-runtime} build-out,
 * not this spike.
 */
package dev.xtrafe.javai.agent;
