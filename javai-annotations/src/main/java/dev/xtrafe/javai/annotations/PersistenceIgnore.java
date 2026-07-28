package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Keeps an {@code @Entity} out of JavAI's entity registration when a package is being scanned -- the
 * declarative counterpart to {@code JavAIPersistenceConfig.Builder.excludeEntityType(...)}, for a type whose
 * owner would rather say "not JavAI's" once, at the class, than maintain a list somewhere else.
 *
 * <p>Reach for it when an {@code @Entity} lives inside a scanned package but is <em>never</em> JavAI's to
 * persist -- it belongs to a different persistence unit or {@code SessionFactory} entirely.
 *
 * <p><b>This marker is global, so it is the wrong tool for a type that is merely backend-specific.</b> A
 * {@code KnowledgeGraph}-typed entity, for instance, is perfectly persistable -- on Neo4j -- and is refused
 * only on Postgres and MongoDB. Annotating it here would exclude it from the Neo4j configuration too, which
 * is the opposite of what its owner wants. For that, exclude it per configuration with
 * {@code JavAIPersistenceConfig.Builder.excludeEntityType(...)}, which says "not <em>this</em> config's"
 * rather than "not JavAI's".
 *
 * <p>Nothing about being non-vectorized calls for exclusion of any kind: a plain {@code @Entity} with no
 * {@code @JavAIVectorizable} is a first-class citizen of a {@code JavAIRepository} and is registered, mapped
 * and served exactly like a vectorized one -- it simply has no vectors. Marking such a type here would drop
 * it from persistence altogether, which is almost certainly not the intent.
 *
 * <p><b>It excludes from scanning, not from reachability.</b> A type named explicitly via
 * {@code entityType(...)} is registered regardless -- naming a specific class is unambiguous intent that
 * beats a blanket marker -- and so is one reached through a registered entity's own fields, because
 * Hibernate cannot map the referencing entity without it. So this keeps a type from being <em>swept in</em>;
 * it cannot sever a mapping something else depends on.
 *
 * <p>See doc/ai-guidance/persistence-support-matrix.md's "Registering entity types" (OMI-214).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PersistenceIgnore {
}
