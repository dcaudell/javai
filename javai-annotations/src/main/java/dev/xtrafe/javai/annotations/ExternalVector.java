package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Declares a vector this class carries but JavAI never computes -- supplied from outside the process, stored,
 * versioned, served and searched exactly like any other (OMI-290).
 *
 * <p>The motivating case is an image embedding: the bytes live in object storage, the model runs in its own
 * container behind a queue, and the answer arrives seconds or minutes later, in a different model and
 * dimensionality from the text vectors on the same object graph. Nothing about that fits
 * {@link Vectorize}'s contract, whose whole shape is "a read of a stale value computes it now" -- so this is
 * a separate declaration rather than a mode of that one.
 *
 * <pre>{@code
 * @Entity
 * @JavAIVectorizable
 * @ExternalVector(name = "pixels", keyField = "contentHash", model = "siglip2-so400m-p14-384/pp1")
 * public class Image extends Asset { ... }
 * }</pre>
 *
 * <h2>Why this is declared on the type, not on the field</h2>
 *
 * The field that identifies the content is very often inherited -- a blob coordinate or content hash on a
 * shared {@code @MappedSuperclass} -- while only some of the subclasses actually have a vector of that kind.
 * Annotations on a field apply to every class that inherits it and cannot be overridden on one subclass, so
 * a field-level declaration would force the whole hierarchy to expect a vector most of it can never have.
 * Declaring on the type also lets one class carry several ({@code @Repeatable}) -- a video with both a
 * keyframe vector and an audio vector, naming the same key field with two different models -- and frees the
 * vector's own name from the field's, so the accessor reads {@code pixelsVector()} rather than being tied to
 * whatever the coordinate column happens to be called.
 *
 * <h2>What the runtime guarantees</h2>
 *
 * <ul>
 *   <li><b>A read never computes and never blocks</b>, under any {@code EmbeddingConsistencyMode} and
 *       including inside a persistence flush. Before a vector is supplied, and after the content it was
 *       computed for has changed, the accessor answers {@code EmbeddingVector.absent()}.</li>
 *   <li><b>A supplied vector is accepted only for the content it was computed for.</b> The supplier passes
 *       the {@link #keyField()} value it saw; if the field has moved on since, the vector is discarded rather
 *       than stored against content it does not describe.</li>
 *   <li><b>Staleness is re-derived on every read</b> by comparing the stored key against the field's current
 *       value -- so, unlike a {@code @Vectorize} field, this does not depend on the mutation being observed
 *       through a woven setter. That is affordable here precisely because the key is a short identifier
 *       rather than the content itself.</li>
 * </ul>
 *
 * @see Vectorize
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ExternalVector.List.class)
public @interface ExternalVector {

    /**
     * This vector's name, unique within the class -- the accessor becomes {@code <name>Vector()}, the
     * persisted row is filed under it, and a repository may declare
     * {@code findNearestBy<Name>Vector(EmbeddingVector, int)}.
     *
     * <p>⚠️ Must not collide with a {@code @Vectorize} field's name (they share one cache slot namespace) nor
     * produce an accessor colliding with a {@code JavAIVectorizable} method. Both are refused at weave time
     * rather than producing a class that misbehaves quietly.
     */
    String name();

    /**
     * The field whose value identifies the content this vector describes -- a content hash, a blob key, a URI.
     *
     * <p>Never the content itself: JavAI does not read bytes. This value is what a supplier echoes back to
     * prove which content it embedded, and what every read compares against to decide whether the stored
     * vector still applies. May be declared on this class or inherited.
     */
    String keyField();

    /**
     * The embedding model this vector comes from, as {@code EmbeddingVector.modelId()} will report it.
     *
     * <p>Fold every dimension of the model's identity into it -- name, weights version, preprocessing
     * version -- because storage is partitioned by this string, so changing it makes the new vectors a
     * separate, additively-migratable set rather than an in-place overwrite of the old ones. A supplied
     * vector whose own {@code modelId()} disagrees with this is refused.
     */
    String model();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        ExternalVector[] value();
    }
}
