package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.annotations.Vectorize;

import java.lang.reflect.Field;

/**
 * Whether a bulk write may assign to a field, and if not, why (OMI-398).
 *
 * <p>A {@code @Modifying} statement writes rows, not objects: no woven accessor fires, so Vector Core is never
 * told the value moved. In memory that would be a staleness lasting until the next read. In a database it is
 * worse and permanent -- since OMI-187 a stored vector is hydrated straight back into a loaded object's cache
 * slots, so the vector computed for the old value is served on every later load, in every process, until
 * something happens to rewrite it. That is the durable form of the mutation rule's failure that
 * {@code SPEC.md} warns about, which is why these are refused when the repository is realized rather than
 * documented as a hazard.
 *
 * <p><b>Why this is a class of its own rather than a private method on the backend.</b> It is the one place
 * outside {@code Containment} that reads {@code @Taggregate}, and {@code javai-tagging}'s
 * {@code OneContainmentImplementationTest} deliberately fails when a second reader appears -- because a second
 * derivation of containment that merely agrees with the first passes every behavioural test right up until it
 * drifts. This is not that: it derives no containment and walks no object graph. It asks only whether writing
 * one named field would invalidate something JavAI derives, which is a guard <em>over</em> Containment's
 * inputs rather than a competing answer to Containment's question. Keeping it in a small, named file is what
 * makes that distinction checkable at a glance, instead of exempting four thousand lines of backend.
 */
final class BulkWriteGuard {

    private BulkWriteGuard() {
    }

    /**
     * Why JavAI derives state from {@code attribute} on {@code owner}, or {@code null} when it does not.
     *
     * <p>The {@code null} case is the common one and must stay cheap to reach: an ordinary column on a
     * vectorized entity is perfectly writable. A summary is arithmetic over vectors, so a column no vector
     * reads cannot move one -- refusing those too would have blocked the whole feature for any entity that
     * happens to carry an embedding.
     *
     * @param owner     the entity the statement actually targets, which is not necessarily the repository's
     *                  own type -- resolved from the parsed statement, so a repository over a plain entity is
     *                  not a way around this
     * @param attribute the entity's own first-level attribute being assigned to
     */
    static String refusalReason(Class<?> owner, String attribute) {
        Field field;
        try {
            field = EntityReflection.findField(owner, attribute);
        } catch (IllegalStateException notAField) {
            return null; // an embeddable path or an XML-mapped property: nothing of JavAI's hangs off it
        }
        if (field.isAnnotationPresent(Vectorize.class)) {
            return "it is @Vectorize, so its embedding would keep the value it had before this write";
        }
        if (field.isAnnotationPresent(Summary.class)) {
            return "it is @Summary, so the summary vectors of the containers it moves this entity between "
                    + "would both be left wrong, with no recomputation queued";
        }
        if (field.isAnnotationPresent(Taggregate.class)) {
            return "it is @Taggregate, so the aggregate taggings derived from its members would drift with "
                    + "nothing to notice";
        }
        return externalVectorKeyReason(owner, attribute);
    }

    /**
     * The {@code @ExternalVector} whose content key this field is, if any.
     *
     * <p>Read from the annotations and walked up the hierarchy by hand, rather than asked of
     * {@code JavAIRuntime}: {@code @ExternalVector} is declared on the <em>type</em> and is not
     * {@code @Inherited}, yet the key field it names is very often the one inherited from a
     * {@code @MappedSuperclass} -- which is exactly the case this check most needs to see.
     */
    private static String externalVectorKeyReason(Class<?> owner, String attribute) {
        for (Class<?> current = owner; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (ExternalVector declared : current.getAnnotationsByType(ExternalVector.class)) {
                if (declared.keyField().equals(attribute)) {
                    return "it is @ExternalVector(\"" + declared.name() + "\")'s key field, so the vector "
                            + "supplied for the old content would go on being served for the new";
                }
            }
        }
        return null;
    }
}
