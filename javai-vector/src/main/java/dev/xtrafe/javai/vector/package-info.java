/**
 * Pure vector/embedding functionality: {@link EmbeddingVector} (the versioned embedding value type), a CPU
 * cosine-similarity backend ({@link VectorMath}), the {@link JavAIEmbeddingProvider} SPI plus two real
 * HTTP-client implementations ({@link TextEmbeddingsInferenceProvider} against the {@code docker/} TEI
 * sidecar, {@link OllamaEmbeddingProvider} for the macOS/Candle-bug workaround -- see
 * {@link LocalEmbeddingDefaults} for which one a given platform gets by default), and the dirty-state
 * bookkeeping primitives ({@link JavAIDirtyTracking}, {@link DirtyTrackingSupport}, the internal
 * {@code IdentityWeakReference} helper its {@code dependents()} set uses).
 *
 * <p><b>Module-placement note:</b> this package deliberately does <em>not</em> contain
 * {@code JavAIVectorizable} (the contract), {@code JavAIRuntime} (the engine implementing it), or any of
 * the {@code JavAIList}/{@code Set}/{@code Map} collection types, even though all of those are just as
 * conceptually "Vector Core" as what's here -- they live in {@code javai-model} instead, for a real,
 * traced-not-assumed dependency reason: {@code JavAIVectorizable.query()} returns {@code JavAIList<T>}, and
 * {@code JavAIList} in turn {@code extends JavAIVectorizable} right back. Two types with a genuine mutual
 * reference can never live in separate modules without either an illegal cycle or an API change -- so
 * wherever {@code JavAIList} ends up, {@code JavAIVectorizable} (and {@code JavAIRuntime}, which
 * constructs a {@code JavAIList} to implement {@code query()}) has to go too. Since {@code JavAIList} has
 * to live upstream of {@code javai-collections} for the same reason it always has (see
 * {@code javai-model}'s own package-info.java), the contract and engine follow it there rather than
 * staying here. What's left in this package is exactly the subset that has zero reference to
 * {@code JavAIList}/{@code Contextable} at all -- confirmed by grepping every file here, not assumed --
 * which is what lets this module stay a true leaf dependency (only {@code javai-annotations}, no
 * collection-shaped or JSON-marshalling concerns of any kind).
 *
 * <p>{@link DirtyTrackingSupport}'s cache accessors ({@code cachedVector()}/{@code cacheVector()}/etc.)
 * are {@code public}, not package-private, specifically so {@code javai-model}'s {@code JavAIRuntime} and
 * concrete collection types (a different module now, not just a different class in this same package) can
 * reach them.
 */
package dev.xtrafe.javai.vector;
