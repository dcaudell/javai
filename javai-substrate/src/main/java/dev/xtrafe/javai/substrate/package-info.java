/**
 * Acceleration Substrate: the ByteBuddy-based weaver that implements
 * {@code dev.xtrafe.javai.model.JavAIVectorizable} and {@code dev.xtrafe.javai.vector.JavAIDirtyTracking}
 * for real on any class carrying {@code @JavAIVectorizable}, and rewrites its annotated fields'
 * conventional setters to call into {@code javai-model}'s {@code JavAIRuntime}.
 *
 * <p>{@link dev.xtrafe.javai.substrate.JavAIWeaver} is pure bytecode-wiring -- field/method synthesis via
 * ByteBuddy's {@code MethodCall}, plus {@link dev.xtrafe.javai.substrate.VectorizeFieldSetterAdvice}/
 * {@link dev.xtrafe.javai.substrate.SummaryOnlyFieldSetterAdvice}/{@link dev.xtrafe.javai.substrate.ConstructorExitAdvice}
 * for setter/constructor instrumentation. Every actual algorithm (dirty tracking, embedding, the graph
 * walk behind {@code query()}, the decay-weighted {@code summaryVector()} formula) lives in
 * {@code JavAIRuntime}, not here -- see that class's javadoc for why. This supersedes an earlier
 * single-field, fake-embedding spike that only proved the load-time weaving mechanism worked at all;
 * {@code VectorizationWeavingTest} now covers the full contract: multiple {@code @Vectorize} fields,
 * {@code summaryVector()} propagating through both a single {@code @Summary} reference and a
 * {@code @Summary} collection, and cycle safety.
 *
 * <p>Deliberately still out of scope: non-conventional setters, and multiple annotated fields sharing one
 * setter.
 */
package dev.xtrafe.javai.substrate;
