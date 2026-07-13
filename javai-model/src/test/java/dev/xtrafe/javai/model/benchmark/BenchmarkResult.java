package dev.xtrafe.javai.model.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One labeled scenario's set of named numeric metrics from a single benchmark run -- e.g. one
 * {@code EmbeddingConsistencyMode}'s throughput/latency numbers, or (in a future persistence-performance
 * or substrate-optimization benchmark) one backend's or weaving strategy's own numbers. Deliberately a
 * flexible {@code String -> Double} map rather than a fixed schema, so every future benchmark suite can
 * reuse {@link BenchmarkHistory}'s comparison machinery without being forced into
 * vectorization-specific metric names.
 */
public record BenchmarkResult(String label, Map<String, Double> metrics) {

    public static Builder builder(String label) {
        return new Builder(label);
    }

    public static final class Builder {

        private final String label;
        private final Map<String, Double> metrics = new LinkedHashMap<>();

        private Builder(String label) {
            this.label = label;
        }

        public Builder metric(String name, double value) {
            metrics.put(name, value);
            return this;
        }

        public BenchmarkResult build() {
            return new BenchmarkResult(label, Map.copyOf(metrics));
        }
    }
}
