/**
 * The formalized home for whatever has to live physically upstream of {@code javai-collections}/
 * {@code javai-completion} for compile-order reasons, rather than because it's vector/embedding core (that
 * lives in {@code javai-vector}, this module's one dependency alongside {@code javai-annotations}). Three
 * groups of types, each here for its own traced-not-assumed reason:
 *
 * <ol>
 *   <li><b>{@link JavAIVectorizable} (the contract) and {@link JavAIRuntime} (the engine implementing
 *       it, including the {@code query()} graph walk and the {@code vector()}/{@code summaryVector()}
 *       lazy-recompute lifecycle from doc/spec/vector-core.md).</b> These are dependency-direction
 *       hostages of {@code JavAIList}: {@code JavAIVectorizable.query()} returns {@code JavAIList<T>}, and
 *       {@code JavAIList} in turn {@code extends JavAIVectorizable} right back. Two types with a genuine
 *       mutual reference can never live in separate modules without either an illegal cycle or an API
 *       change -- confirmed this isn't avoidable by trying to split {@code query()} out into its own
 *       interface: {@code JavAIRuntime.summaryVector()}'s cycle-safe walk checks
 *       {@code value instanceof JavAIVectorizable} to decide whether a {@code @Summary}-annotated
 *       collection field should contribute its own {@code summaryVector()} (real, tested behavior --
 *       {@code Article.comments}, a {@code JavAIList}, is exactly this case), so {@code JavAIList} needs
 *       the <em>whole</em> {@code JavAIVectorizable} contract, not just {@code query()}. There's no clean
 *       seam here; the two types are irreducibly one unit.</li>
 *   <li><b>{@link JavAISortable}, {@link JavAIList}/{@link JavAISet}/{@link JavAIMap}, and their concrete
 *       implementations ({@link JavAIArrayList}, {@link JavAILinkedHashSet}, {@link JavAILinkedHashMap})
 *       plus {@link CollectionVectorSupport}</b> -- conceptually "Vector Collections" per
 *       doc/spec/vector-collections.md, physically here for the same reason as group 1:
 *       {@code javai-collections} depends on this module, not the reverse, and {@code KnowledgeGraph}/
 *       {@code SubgraphResult}/{@code VectorIndex} (which actually belong in {@code javai-collections})
 *       depend on the types here.</li>
 *   <li><b>{@link Contextable}, {@link PromptContext}, {@link ContextableObject}, and the package-private
 *       {@code PlainTextEntry}</b> -- conceptually "Completion Fabric"'s RAG-integration primitives per
 *       doc/spec/completion-fabric.md, physically here because {@code Contextable.toContext(PromptContext)}
 *       references {@code PromptContext}, and {@code JavAIList}/{@code Set}/{@code Map} (group 2, already
 *       here) all implement {@code Contextable} -- so both types must live wherever those three collection
 *       interfaces do, or this module would need an illegal reverse dependency on {@code javai-completion}.
 * </ol>
 *
 * <p>The whitepaper's seven-extension-area taxonomy is conceptual, not a physical module map -- this
 * module is the accumulated evidence that "Vector Core," "Vector Collections," and "Completion Fabric"
 * each have a piece that can't physically live where their own dedicated module (or, for Vector Core,
 * {@code javai-vector}) lives, for the identical structural reason each time. {@code javai-collections}'
 * own {@code KnowledgeGraph}/{@code SubgraphResult}/{@code VectorIndex} do not yet implement
 * {@code Contextable} -- deferred pending a cycle-safe design, since GSON's default reflection (unlike
 * this package's own {@code JavAIRuntime.enterSummaryComputation}/{@code exitSummaryComputation} guard)
 * has no protection against the cycles a graph-shaped type can legitimately contain. See
 * {@code PromptContext}'s own javadoc.
 */
package dev.xtrafe.javai.model;
