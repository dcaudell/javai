package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.persistence.scanned.AnnotationExcludedEntity;
import dev.xtrafe.javai.persistence.scanned.ExcludedByNameEntity;
import dev.xtrafe.javai.persistence.scanned.ScannedOnlyEntity;
import dev.xtrafe.javai.persistence.scanned.excluded.ExcludedByPackageEntity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three ways to keep an {@code @Entity} out of what {@code entityPackages(...)} sweeps in (OMI-214).
 *
 * <h2>What exclusion is for, and what it is emphatically not for</h2>
 *
 * Scanning validates everything it finds, which is right -- an entity JavAI cannot map must not be
 * registered silently, since Hibernate maps it regardless and JavAI's half is then wrong. Exclusion exists
 * for the other case: an {@code @Entity} that sits inside a scanned package but belongs to a <em>different</em>
 * persistence unit or {@code SessionFactory}.
 *
 * <p>It is <b>not</b> for non-vectorized entities. A plain {@code @Entity} with no {@code @JavAIVectorizable}
 * is a first-class citizen of a {@code JavAIRepository} -- registered, mapped and served exactly like a
 * vectorized one, it simply has no vectors. Serving both kinds from one repository type is the point of the
 * design, so excluding a type for being non-vectorized would remove it from persistence entirely. Every
 * fixture excluded below is an ordinary non-vectorized entity chosen to make that distinction concrete:
 * they are excluded because a test says so, never because of what they are.
 *
 * <p>No container needed -- scanning and exclusion are resolved by reflection, before any connection.
 */
class EntityScanExclusionTest {

    private static final String SCANNED = "dev.xtrafe.javai.persistence.scanned";

    private static Set<Class<?>> scan(JavAIPersistenceConfig config) {
        return EntityPackageScanner.scan(config.entityPackages(),
                Thread.currentThread().getContextClassLoader(),
                config.excludedEntityTypes(), config.excludedEntityPackages());
    }

    /** The baseline: with no exclusions, everything under the package is found, sub-packages included. */
    @Test
    void scanningFindsEverythingUnderThePackageByDefault() {
        Set<Class<?>> found = scan(JavAIPersistenceConfig.builder().entityPackages(SCANNED).build());

        assertTrue(found.contains(ScannedOnlyEntity.class));
        assertTrue(found.contains(ExcludedByNameEntity.class), "not excluded here, so it must be found");
        assertTrue(found.contains(ExcludedByPackageEntity.class), "sub-packages are scanned too");
    }

    @Test
    void aTypeExcludedByNameIsNotScanned() {
        Set<Class<?>> found = scan(JavAIPersistenceConfig.builder()
                .entityPackages(SCANNED)
                .excludeEntityType(ExcludedByNameEntity.class)
                .build());

        assertFalse(found.contains(ExcludedByNameEntity.class));
        assertTrue(found.contains(ScannedOnlyEntity.class), "its neighbours are unaffected");
    }

    @Test
    void aPackageExcludedByNameIsNotScanned() {
        Set<Class<?>> found = scan(JavAIPersistenceConfig.builder()
                .entityPackages(SCANNED)
                .excludeEntityPackages(SCANNED + ".excluded")
                .build());

        assertFalse(found.contains(ExcludedByPackageEntity.class));
        assertTrue(found.contains(ScannedOnlyEntity.class));
    }

    /** A trailing {@code .*} is the obvious way to write a package pattern, so it has to work. */
    @Test
    void aPackageExcludedWithAWildcardSuffixIsNotScanned() {
        Set<Class<?>> found = scan(JavAIPersistenceConfig.builder()
                .entityPackages(SCANNED)
                .excludeEntityPackages(SCANNED + ".excluded.*")
                .build());

        assertFalse(found.contains(ExcludedByPackageEntity.class));
        assertTrue(found.contains(ScannedOnlyEntity.class));
    }

    /**
     * Exclusion matches on package boundaries. A bare prefix test would make
     * {@code excludeEntityPackages("...scanned")} also swallow a hypothetical {@code ...scannedElsewhere},
     * which is the kind of silent over-exclusion that produces a mapping failure far from its cause.
     */
    @Test
    void excludingAPackageDoesNotExcludeOneThatMerelySharesItsPrefix() {
        Set<Class<?>> found = scan(JavAIPersistenceConfig.builder()
                .entityPackages(SCANNED)
                .excludeEntityPackages(SCANNED + ".exclu")
                .build());

        assertTrue(found.contains(ExcludedByPackageEntity.class),
                "'...scanned.exclu' must not match '...scanned.excluded'");
    }

    /** The declarative form: the type says so itself, with nothing named in the configuration. */
    @Test
    void aTypeAnnotatedPersistenceIgnoreIsNotScanned() {
        Set<Class<?>> found = scan(JavAIPersistenceConfig.builder().entityPackages(SCANNED).build());

        assertFalse(found.contains(AnnotationExcludedEntity.class),
                "@PersistenceIgnore excludes without any configuration naming the type");
        assertTrue(found.contains(ScannedOnlyEntity.class));
    }

    /**
     * Naming a class outright beats a blanket exclusion. {@code entityType(...)} is unambiguous intent about
     * one specific class, where an excluded package is a statement about a region -- so the specific wins,
     * and a type can be pulled back in without restructuring packages.
     */
    @Test
    void explicitlyNamingATypeOverridesAPackageExclusion() {
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .entityPackages(SCANNED)
                .excludeEntityPackages(SCANNED + ".excluded")
                .entityType(ExcludedByPackageEntity.class)
                .build();

        assertFalse(scan(config).contains(ExcludedByPackageEntity.class),
                "scanning itself still honours the exclusion");
        assertTrue(config.additionalEntityTypes().contains(ExcludedByPackageEntity.class),
                "but the explicitly named type is registered anyway, which is what the backend acts on");
    }

    @Test
    void severalExclusionsCombine() {
        Set<Class<?>> found = scan(JavAIPersistenceConfig.builder()
                .entityPackages(SCANNED)
                .excludeEntityTypes(List.of(ExcludedByNameEntity.class))
                .excludeEntityPackages(SCANNED + ".excluded")
                .build());

        assertFalse(found.contains(ExcludedByNameEntity.class));
        assertFalse(found.contains(ExcludedByPackageEntity.class));
        assertFalse(found.contains(AnnotationExcludedEntity.class));
        assertTrue(found.contains(ScannedOnlyEntity.class), "and what was not excluded survives all three");
    }
}
