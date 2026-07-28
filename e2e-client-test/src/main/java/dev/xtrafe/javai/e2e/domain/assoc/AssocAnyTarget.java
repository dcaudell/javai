package dev.xtrafe.javai.e2e.domain.assoc;

/**
 * The common interface an {@code @Any} association points at. Deliberately a plain interface: no JPA
 * annotations, no shared table, no mapped supertype.
 *
 * <p>That is the whole point of {@code @Any} -- a to-one whose target may be any of several *unrelated*
 * entities -- and it is also exactly why OMI-212 happened. JavAI discovers related entity types by walking
 * declared field types; this type is not an {@code @Entity}, so the walk learns nothing and the concrete
 * targets have to be picked up from {@code @AnyDiscriminatorValue} instead.
 *
 * <p>Its implementors deliberately differ in the one dimension OMI-161 cared about: {@link AssocAnyVectorizable}
 * is {@code @JavAIVectorizable}, {@link AssocAnyPlain} is not. A polymorphic reference can therefore resolve
 * to either kind at runtime, which is a harder case than any fixed-type association in this matrix.
 */
public interface AssocAnyTarget {

    String label();
}
