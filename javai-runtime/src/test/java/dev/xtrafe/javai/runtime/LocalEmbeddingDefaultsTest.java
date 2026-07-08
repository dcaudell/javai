package dev.xtrafe.javai.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEmbeddingDefaultsTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(LocalEmbeddingDefaults.OVERRIDE_PROPERTY);
    }

    @Test
    void detectPlatformNeverReturnsNull() {
        assertNotNull(LocalEmbeddingDefaults.detectPlatform());
    }

    @Test
    void overrideToOllamaWinsRegardlessOfHostOs() {
        System.setProperty(LocalEmbeddingDefaults.OVERRIDE_PROPERTY, "ollama");
        assertTrue(LocalEmbeddingDefaults.preferOllama());
        assertEquals("ollama/ollama:latest", LocalEmbeddingDefaults.dockerImage());
        assertEquals(11434, LocalEmbeddingDefaults.containerPort());
        assertEquals("/", LocalEmbeddingDefaults.healthCheckPath());
        assertEquals("qwen3-embedding:0.6b", LocalEmbeddingDefaults.modelIdentifierForContainerStartup());
        assertInstanceOf(OllamaEmbeddingProvider.class, LocalEmbeddingDefaults.create(URI.create("http://localhost:11434")));
    }

    @Test
    void overrideToTeiWinsRegardlessOfHostOs() {
        System.setProperty(LocalEmbeddingDefaults.OVERRIDE_PROPERTY, "tei");
        assertTrue(!LocalEmbeddingDefaults.preferOllama());
        assertEquals("ghcr.io/huggingface/text-embeddings-inference:cpu-1.9", LocalEmbeddingDefaults.dockerImage());
        assertEquals(80, LocalEmbeddingDefaults.containerPort());
        assertEquals("/health", LocalEmbeddingDefaults.healthCheckPath());
        assertEquals("Qwen/Qwen3-Embedding-0.6B", LocalEmbeddingDefaults.modelIdentifierForContainerStartup());
        assertInstanceOf(TextEmbeddingsInferenceProvider.class,
                LocalEmbeddingDefaults.create(URI.create("http://localhost:8080")));
    }

    @Test
    void overrideIsCaseInsensitiveAndTrimmed() {
        System.setProperty(LocalEmbeddingDefaults.OVERRIDE_PROPERTY, "  OLLAMA  ");
        assertTrue(LocalEmbeddingDefaults.preferOllama());
    }

    @Test
    void noOverrideFallsBackToActualHostPlatformDetection() {
        boolean expected = LocalEmbeddingDefaults.detectPlatform() == LocalEmbeddingDefaults.Platform.MACOS;
        assertEquals(expected, LocalEmbeddingDefaults.preferOllama());
    }
}
