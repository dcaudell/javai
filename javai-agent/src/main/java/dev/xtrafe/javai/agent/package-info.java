/**
 * Acceleration Substrate: the ByteBuddy-based weaver that implements the
 * {@code JavAIVectorizable}/{@code JavAIGraphNode} interfaces (defined in
 * {@code javai-runtime}/{@code javai-collections}) on any class carrying the
 * corresponding annotation from {@code javai-annotations}, and rewrites mutating
 * setters to call the runtime's {@code markDirty()}/{@code propagateDirty()} hooks.
 *
 * <p>Not yet implemented. See doc/spec/acceleration-substrate.md and CLAUDE.md's build
 * order: this is the highest-novelty, highest-risk module in Phase 0 -- prove a minimal
 * spike (one woven setter on a toy class, correct dirty-flag behavior, correct lazy
 * recomputation on next read) before relying on it elsewhere.
 */
package dev.xtrafe.javai.agent;
