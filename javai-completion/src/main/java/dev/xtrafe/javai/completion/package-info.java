/**
 * Completion Fabric: {@code CompletionRequest}/{@code CompletionResult}, {@code Cortex} (six providers --
 * OpenAI, Anthropic, Groq, vLLM, Ollama, Replicate -- wrapping Spring AI's {@code ChatModel} where one
 * exists), and provider-specific tuning parameters via {@code CompletionRequest.providerOptions()}.
 *
 * <p>{@code PromptContext} and {@code Contextable} -- the RAG-integration primitives grounding a completion
 * in a {@code JavAIList}/{@code Set}/{@code Map} -- live in {@code dev.xtrafe.javai.runtime}, not here; see
 * that package's own {@code package-info.java} for why. This module consumes them directly, the same way
 * it consumes {@code JavAIList} itself.
 *
 * <p>See doc/spec/completion-fabric.md for design goals, the prior-art positioning against Spring
 * AI/LangChain4j, and worked examples; see this module's own README for exactly what's implemented vs.
 * deferred.
 */
package dev.xtrafe.javai.completion;
