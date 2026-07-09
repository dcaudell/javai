/**
 * The full JavAI Extensions annotation vocabulary, across five extension areas:
 *
 * <ul>
 *   <li><b>Vector Core</b> (search-visibility hat): {@link dev.xtrafe.javai.annotations.JavAIVectorizable},
 *       {@link dev.xtrafe.javai.annotations.Vectorize}, {@link dev.xtrafe.javai.annotations.VectorizeIgnore},
 *       {@link dev.xtrafe.javai.annotations.SearchVisibility}, {@link dev.xtrafe.javai.annotations.Summary},
 *       {@link dev.xtrafe.javai.annotations.EmbeddingModel}.
 *   <li><b>Vector Collections</b>: {@link dev.xtrafe.javai.annotations.JavAIGraphNode},
 *       {@link dev.xtrafe.javai.annotations.JavAIEdge}.
 *   <li><b>Completion Fabric</b>: {@link dev.xtrafe.javai.annotations.PromptContext} -- a field-level
 *       allowlist for {@code dev.xtrafe.javai.runtime.PromptContext.defaultMarshall(Object)}'s GSON
 *       rendering. Unlike every other annotation here, no weaver processes this one -- GSON's own
 *       reflection (an {@code ExclusionStrategy} in that class) reads it directly at marshalling time, not
 *       at class-load time.
 *   <li><b>Codegen Guidance</b> (LLM/codegen-guidance hat): {@link dev.xtrafe.javai.annotations.Requires},
 *       {@link dev.xtrafe.javai.annotations.Ensures}, {@link dev.xtrafe.javai.annotations.Invariant},
 *       {@link dev.xtrafe.javai.annotations.Intent}, {@link dev.xtrafe.javai.annotations.AgentWritable},
 *       {@link dev.xtrafe.javai.annotations.Frozen}, {@link dev.xtrafe.javai.annotations.HumanOnly},
 *       {@link dev.xtrafe.javai.annotations.Nondeterministic}, {@link dev.xtrafe.javai.annotations.Costly},
 *       {@link dev.xtrafe.javai.annotations.Provenance}.
 *   <li><b>Agentic Supervision</b>: {@link dev.xtrafe.javai.annotations.SyncSupervision},
 *       {@link dev.xtrafe.javai.annotations.AsyncSupervision}, {@link dev.xtrafe.javai.annotations.SupervisionPointcut}.
 *       Run-time execution control (an LLM-backed listener may observe or intervene on a call), a distinct
 *       axis from Codegen Guidance's design-time edit permissions above -- see doc/spec/agentic-supervision.md
 *       for why the two aren't the same thing despite both governing "what may an agent do here."
 * </ul>
 *
 * These are plain annotation definitions with no processing logic of their own -- the weaver in
 * {@code javai-agent} (Vector Core/Collections) or {@code javai-supervision} (Agentic Supervision -- its
 * own, independent weaver; see that module's package-info for why it doesn't reuse javai-agent's) gives
 * most of them behavior; {@code PromptContext} (Completion Fabric) is the one exception, read directly by
 * {@code javai-runtime} rather than any weaver -- see that annotation's own javadoc. See {@code doc/spec/}
 * for the full design of each area. Before generating or modifying code that carries the Codegen Guidance
 * annotations, read {@code doc/JavAI_Codegen_Guidance.md} in full.
 *
 * <p>The proposed (not-yet-implemented) {@code @Summary} tuning parameters described in
 * {@code doc/spec/vector-core.md} (decay, maxStack, maxDepth, aggregation, edgeKind) are
 * deliberately NOT present on {@link dev.xtrafe.javai.annotations.Summary} below -- they
 * are specification proposals, not Phase 0 scope.
 */
package dev.xtrafe.javai.annotations;
