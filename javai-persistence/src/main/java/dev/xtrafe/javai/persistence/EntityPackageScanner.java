package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.PersistenceIgnore;
import jakarta.persistence.Entity;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finds every {@code @Entity} class under a set of packages, for
 * {@link JavAIPersistenceConfig.Builder#entityPackages(String...)}.
 *
 * <p>Exists to make registration ordering irrelevant (OMI-214). JavAI otherwise learns about an entity only
 * when a repository is realized for it or for something referencing it, and the Postgres backend freezes
 * Hibernate's metadata at the first actual repository call -- so whether an application booted depended on
 * the order its repositories happened to be created in, which nothing local could check. Scanning resolves
 * the entity set from configuration instead, before anything can be built.
 *
 * <p>Built on Spring's {@link ClassPathScanningCandidateComponentProvider}, which already handles the parts
 * of classpath scanning that are tedious to get right -- directories and jars alike, nested and inner
 * classes, and reading annotations from class <em>metadata</em> rather than by loading the class (so naming
 * a broad package neither initializes anything nor risks a static initializer running early). Spring is
 * already a hard dependency of this module for the MongoDB backend and {@code spring-orm}; the
 * {@code spring-context} artifact it lives in is now declared explicitly rather than being relied on
 * transitively.
 *
 * <p>Deliberately <b>not</b> filtered to {@code @JavAIVectorizable}: a plain {@code @Entity} is a
 * first-class citizen of a {@code JavAIRepository} (it simply gets no vectors), and Hibernate needs every
 * entity in its metadata regardless of whether JavAI has anything extra to do with it.
 */
final class EntityPackageScanner {

    private EntityPackageScanner() {
    }

    /**
     * Every {@code @Entity} class under {@code packages}, in a stable order.
     *
     * <p>An empty result for a package that was named is not treated as an error here. It usually means a
     * typo, but it can also legitimately mean "this module contributes no entities in this deployment", and
     * failing a whole application's persistence over the ambiguity would be worse than registering nothing.
     */
    static Set<Class<?>> scan(Set<String> packages, ClassLoader classLoader,
            Set<Class<?>> excludedTypes, Set<String> excludedPackages) {
        if (packages.isEmpty()) {
            return Set.of();
        }
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader(classLoader));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        Set<Class<?>> found = new LinkedHashSet<>();
        for (String basePackage : packages) {
            scanner.findCandidateComponents(basePackage).forEach(candidate -> {
                String className = candidate.getBeanClassName();
                if (className == null) {
                    return;
                }
                if (isExcludedByPackage(className, excludedPackages)) {
                    return;
                }
                try {
                    Class<?> type = Class.forName(className, false, classLoader);
                    if (excludedTypes.contains(type)
                            || type.isAnnotationPresent(PersistenceIgnore.class)) {
                        return;
                    }
                    found.add(type);
                } catch (ClassNotFoundException | LinkageError e) {
                    // Scanning reads metadata, so a class can be named here and still fail to load -- an
                    // unresolvable dependency, most often. Reported rather than skipped: an @Entity that
                    // exists on the classpath but cannot be loaded will not be in Hibernate's metadata
                    // either, and discovering that later as a mapping failure is strictly harder to
                    // diagnose than being told now, at registration, which class and which package.
                    throw new IllegalStateException("Found @Entity " + className + " while scanning "
                            + basePackage + ", but it could not be loaded. Either narrow the package passed "
                            + "to entityPackages(...) or make the type loadable at runtime.", e);
                }
            });
        }
        return found;
    }

    /**
     * Whether {@code className} sits in an excluded package or beneath one.
     *
     * <p>Matching is on package boundaries, so excluding {@code com.example.foo} covers
     * {@code com.example.foo.Bar} and {@code com.example.foo.sub.Baz} but never {@code com.example.foobar} --
     * a prefix test alone would quietly exclude the latter. A trailing {@code .*} is stripped first, since
     * that is the natural way to write a package pattern and refusing it would be a pointless trap.
     */
    private static boolean isExcludedByPackage(String className, Set<String> excludedPackages) {
        for (String excluded : excludedPackages) {
            String prefix = excluded.endsWith(".*")
                    ? excluded.substring(0, excluded.length() - 2)
                    : excluded;
            if (className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }
}
