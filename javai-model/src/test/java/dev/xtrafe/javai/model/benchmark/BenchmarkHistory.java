package dev.xtrafe.javai.model.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists a benchmark suite's {@link BenchmarkResult}s to {@code benchmark-results/<suiteName>.json}
 * (GSON -- already a main dependency of this module, see {@code PromptContext}), prints a before/after
 * comparison against whatever was recorded there from the previous run, then overwrites the file with the
 * current results as the new baseline for next time.
 *
 * <p>Deliberately generic ({@code String -> Double} metrics, not vectorization-specific) so a future
 * persistence-performance or substrate-optimization benchmark -- in this module or another -- can reuse
 * this exact pattern (copy this class, same as this project already duplicates small test-only fixtures
 * like {@code FakeEmbeddingProvider} per module rather than sharing test code across module boundaries)
 * without needing to invent its own comparison/persistence logic.
 *
 * <p>Results live in a plain top-level {@code benchmark-results/} directory -- not under {@code src/},
 * since it's generated data, but checked into git (unlike {@code target/}) so history survives a clean
 * build and is reviewable/diffable like any other tracked file. Resolved relative to the module's own
 * working directory, which Surefire sets to the module's basedir by default.
 */
public final class BenchmarkHistory {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path RESULTS_DIR = Path.of("benchmark-results");
    private static final Type RESULTS_TYPE = new TypeToken<Map<String, Map<String, Double>>>() { }.getType();

    private BenchmarkHistory() {
    }

    /** Prints a per-label, per-metric comparison against the last recorded run (if any), then saves
     *  {@code current} as the new baseline. */
    public static void recordAndCompare(String suiteName, List<BenchmarkResult> current) {
        Path file = RESULTS_DIR.resolve(suiteName + ".json");
        Map<String, Map<String, Double>> previous = load(file);

        System.out.println();
        System.out.println("==== " + suiteName + ": current vs. previous run (" + file + ") ====");
        for (BenchmarkResult result : current) {
            System.out.println(result.label() + ":");
            Map<String, Double> previousMetrics = previous.get(result.label());
            for (Map.Entry<String, Double> entry : result.metrics().entrySet()) {
                String name = entry.getKey();
                double value = entry.getValue();
                Double previousValue = previousMetrics == null ? null : previousMetrics.get(name);
                if (previousValue != null) {
                    double percentChange = previousValue == 0 ? 0 : (value - previousValue) / previousValue * 100;
                    System.out.printf("  %-24s %14.3f   (was %14.3f, %+.1f%%)%n",
                            name, value, previousValue, percentChange);
                } else {
                    System.out.printf("  %-24s %14.3f   (no previous baseline)%n", name, value);
                }
            }
        }
        System.out.println();

        save(file, current);
    }

    private static Map<String, Map<String, Double>> load(Path file) {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Map<String, Double>> loaded = GSON.fromJson(reader, RESULTS_TYPE);
            return loaded == null ? Map.of() : loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read benchmark baseline " + file, e);
        }
    }

    private static void save(Path file, List<BenchmarkResult> current) {
        Map<String, Map<String, Double>> toSave = new LinkedHashMap<>();
        for (BenchmarkResult result : current) {
            toSave.put(result.label(), result.metrics());
        }
        try {
            Files.createDirectories(RESULTS_DIR);
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(toSave, RESULTS_TYPE, writer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write benchmark baseline " + file, e);
        }
    }
}
