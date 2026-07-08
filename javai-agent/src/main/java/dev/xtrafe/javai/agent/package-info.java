/**
 * Acceleration Substrate: the ByteBuddy-based weaver that implements
 * {@code dev.xtrafe.javai.runtime.JavAIVectorizable} and {@code JavAIDirtyTracking} for real on any class
 * carrying {@code @JavAIVectorizable}, and rewrites its annotated fields' conventional setters to call
 * into {@code javai-runtime}'s {@code JavAIRuntime}.
 *
 * <p>{@link dev.xtrafe.javai.agent.JavAIWeaver} is pure bytecode-wiring -- field/method synthesis via
 * ByteBuddy's {@code MethodCall}, plus {@link dev.xtrafe.javai.agent.VectorizeFieldSetterAdvice}/
 * {@link dev.xtrafe.javai.agent.SummaryOnlyFieldSetterAdvice}/{@link dev.xtrafe.javai.agent.ConstructorExitAdvice}
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
package dev.xtrafe.javai.agent;
