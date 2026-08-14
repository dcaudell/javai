package dev.xtrafe.javai.tagging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * There is exactly one implementation of parents-from-child, and the membership snapshot does not come
 * back (OMI-304).
 *
 * <p>A source scan rather than a behavioural test, because what is being prevented is <em>a second copy
 * appearing</em> -- and a second copy that happens to agree with the first passes every behavioural test
 * there is, right up until the day it drifts. Taggregate already had one of these: a
 * {@code javai_taggregate_members} table holding what the join tables held, which could not answer for a
 * container nothing had reconciled yet and had to be deleted. This test is what makes reintroducing it
 * fail loudly.
 */
class OneContainmentImplementationTest {

    /** Module roots relative to this module's own directory, which is where surefire runs. */
    private static final List<Path> MAIN_SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("..", "javai-persistence", "src", "main", "java"));

    private static Stream<Path> javaSources() {
        return MAIN_SOURCE_ROOTS.stream().flatMap(root -> {
            try {
                return Files.exists(root) ? Files.walk(root).filter(p -> p.toString().endsWith(".java"))
                        : Stream.<Path>empty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code @Taggregate} is read to discover containment in exactly one place.
     *
     * <p>{@code Containment} answers "which containers hold this member" and "which members does this
     * container hold" for every backend, from the declared model plus the stored relationships. Anything
     * else reading the annotation to walk fields would be a second answer to the same question -- which is
     * not hypothetical: {@code TaggregateReflection} used to do exactly that, from a loaded object graph,
     * and threw {@code LazyInitializationException} on the lazy collections that are the normal shape.
     *
     * <p>The one permitted reader outside {@code Containment} is {@code TaggregateReflection}, which reads
     * the annotation only for its TYPE-level {@code concatenate} flag -- a different question entirely, and
     * asserted below to hold no field walk.
     */
    @Test
    void onlyContainmentDerivesMembershipFromTheAnnotation() {
        List<Path> offenders = javaSources()
                .filter(file -> read(file).contains("Taggregate.class"))
                .filter(file -> !List.of("Containment.java", "TaggregateReflection.java")
                        .contains(file.getFileName().toString()))
                .toList();
        assertTrue(offenders.isEmpty(),
                () -> "containment belongs to Containment alone; a second reader of @Taggregate appeared in "
                        + offenders + ". Extend Containment (as @Taggregate did) rather than copying it.");

        String reflection = read(Path.of("src", "main", "java", "dev", "xtrafe", "javai", "tagging",
                "TaggregateReflection.java"));
        assertTrue(!reflection.contains("getDeclaredFields"),
                "TaggregateReflection must not walk fields -- membership comes from Containment, and an"
                        + " object-graph walk is what required a session and threw on lazy collections");
    }

    /**
     * The membership snapshot stays deleted. The only mention permitted anywhere is the migration that
     * drops it -- so an accidental resurrection, or a backend quietly keeping its own equivalent, fails.
     */
    @Test
    void theMembershipSnapshotDoesNotComeBack() {
        List<Path> mentions = javaSources()
                .filter(file -> read(file).contains("\"javai_taggregate_members\"")
                        || read(file).contains("EXISTS javai_taggregate_members"))
                .toList();
        assertEquals(1, mentions.size(),
                () -> "javai_taggregate_members should survive only as the DROP migration, but is mentioned"
                        + " in " + mentions);
        assertTrue(read(mentions.get(0)).contains("DROP TABLE IF EXISTS javai_taggregate_members"),
                "the one remaining mention must be the migration that drops it");
    }

    /** The loader callback the adopter had to supply is gone from the public surface. */
    @Test
    void noPublicApiTakesALoaderOrAContainerInstance() {
        String repository = read(Path.of("src", "main", "java", "dev", "xtrafe", "javai", "tagging",
                "JavAITagRepository.java"));
        assertTrue(repository.contains("public int rebuildTaggregates()"),
                "rebuildTaggregates() is the one public Taggregate entry point");
        for (String removed : List.of("public void reconcileTaggregate", "public void markTaggregateStale",
                "public int reconcilePendingTaggregates")) {
            assertTrue(!repository.contains(removed),
                    () -> removed + " is internal now -- an adopter must not orchestrate reconciliation");
        }
    }
}
