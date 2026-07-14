/**
 * Tagging: {@code @Taggable} objects, recursive {@code Tag}/{@code TagSet}, LLM-based classification via
 * {@code JavAITagging}, and tag-collection similarity search. See doc/spec/tagging.md for the full design
 * -- primitives table, worked examples, the tag-summary-vector index, and the three-backend persistence
 * shape.
 *
 * <p><b>This module is a stub as of this commit.</b> No source exists here yet beyond this file --
 * scaffolding only (this package, the module's {@code pom.xml}, and its place in the root reactor), left
 * for implementation to follow doc/spec/tagging.md directly rather than guessing intent from a
 * half-built module. Do not assume anything described in that spec file is actually implemented until this
 * package has real, tested classes in it and its own README (once written) says so -- the same rule
 * {@code SPEC.md}'s own "Current status" section already applies to every other module.
 *
 * <p>Depends on {@code javai-vector}/{@code javai-model} ({@code Tag} is itself {@code @JavAIVectorizable}),
 * {@code javai-collections} ({@code VectorIndex<T>}, reused rather than reinvented for tag-similarity
 * search), {@code javai-persistence} (three-backend persisted storage), {@code javai-completion}
 * (classification is an LLM call via {@code Cortex}), and -- unlike every module before it in the reactor
 * -- {@code javai-substrate} directly, since this module ships its own pre-woven {@code Tag}/{@code TagSet}
 * rather than only providing machinery for a consumer's classes to be woven. See doc/spec/tagging.md's "A
 * new structural situation: this module weaves itself" for why that matters at build time.
 */
package dev.xtrafe.javai.tagging;
