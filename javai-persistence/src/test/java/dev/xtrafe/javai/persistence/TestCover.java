package dev.xtrafe.javai.persistence;

/**
 * The common interface an {@code @Any} association points at -- deliberately a plain interface with no JPA
 * annotations and no shared table, which is the entire point of {@code @Any}: a to-one association whose
 * target may be any of several <em>unrelated</em> entities.
 *
 * <p>It is also precisely why OMI-212 happened. JavAI discovers related entity types by walking each
 * field's declared type; this one is not an {@code @Entity}, so the walk learns nothing and the concrete
 * targets named in {@code @AnyDiscriminatorValue} were never registered.
 */
interface TestCover {

    String label();

    /**
     * The same value under a JavaBean name, so {@code PropertyPath} can resolve {@code cover.label} and the
     * derived-finder machinery gets far enough to refuse traversing <em>through</em> the {@code @Any}
     * (OMI-407). Without a resolvable property the parser rejects the path first, for an unrelated reason,
     * and the backend's own refusal is never reached -- which is a worse message and an untested branch.
     */
    String getLabel();
}
