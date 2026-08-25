package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Marks a field or class's contribution to a container's hierarchical summary vector.
 * See doc/spec/vector-core.md for the decay-weighted recursive summary-vector formula
 * this annotation feeds. Proposed tuning parameters (decay, maxStack, maxDepth,
 * aggregation, edgeKind) are specification proposals only -- NOT Phase 0 -- and are
 * deliberately not present here yet.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface Summary {

    /**
     * Opts this element into <b>concatenated text vectoring</b> (OMI-191): assembling real text from an
     * object graph into one string and embedding that, as against {@code summaryVector()}'s arithmetic over
     * already-computed vectors.
     *
     * <p>Defaults to {@code false}, so nothing changes for existing consumers, and nothing is ever
     * accumulated unless asked for. The library cannot know whether folding a {@code Song}'s lyrics into an
     * {@code Album} is meaningful for a given domain, so it must not guess -- which is why all three
     * placements below are independent opt-ins rather than one switch that cascades.
     *
     * <table border="1">
     * <caption>What this means where you put it</caption>
     * <tr><th>Placement</th><th>Meaning</th></tr>
     * <tr><td>On a <b>TYPE</b></td>
     *     <td>This class produces a concatenated text vector from its own {@code @Vectorize} fields.</td></tr>
     * <tr><td>On a <b>FIELD</b> referencing a vectorizable</td>
     *     <td>Absorb that child's concatenated text into mine.</td></tr>
     * <tr><td>On a <b>FIELD</b> holding a JavAI collection</td>
     *     <td>Aggregate the concatenated text of the collection's members into mine.</td></tr>
     * </table>
     *
     * <p>A JavAI collection always <em>can</em> aggregate; whether it does is decided by the <b>owning
     * field</b>, never by the collection itself.
     *
     * <p><b>This adds to {@code @Summary}'s existing meaning rather than replacing it.</b> A field annotated
     * {@code @Summary(concatenate = true)} still contributes to {@code summaryVector()} exactly as a plain
     * {@code @Summary} field does -- the flag opts in to text accumulation as well, not instead. There is
     * deliberately no way to accumulate a child's text while excluding it from the summary vector: no use
     * case demanded it, and a second orthogonal flag would be a worse default than an honest restriction.
     *
     * <p>Note also that the two propagation rules genuinely differ. {@code summaryVector()} lets a node
     * reachable by two paths stack additively, which is meaningful vector arithmetic; concatenated text
     * <b>colours each node so it contributes exactly once</b>, because repeated text merely skews an
     * embedding. See doc/spec/vector-core.md.
     *
     * <p>Before this flag existed, {@code @Summary} on a TYPE was inert -- the target was declared but every
     * reader was field-only. That is what left the TYPE placement free to be given this meaning.
     */
    boolean concatenate() default false;

    /**
     * Opts this <b>container type</b> into having its <b>per-model summary vectors persisted</b>, so they can
     * be ranked against a real index instead of folded in memory on every query (OMI-458).
     *
     * <p>{@code summaryVector(String modelId)} (OMI-290) already computes a container's summary restricted
     * to one embedding model -- what its subtree <em>looks</em> like, as against what it <em>reads</em>
     * like. Nothing stored it. The persistence bridge writes only the ambient text model's row, so a model
     * that arrives exclusively through {@code @ExternalVector} -- image pixels, an audio waveform -- has a
     * computable container summary and nowhere to rank it. This flag is what puts it in the
     * {@code javai_summary_vectors__<model>} table the bridge already names and already indexes.
     *
     * <p><b>Which models</b> is derived, not configured: every model declared by an {@code @ExternalVector}
     * anywhere in this container's {@code @Summary} subtree, transitively, minus the ambient one (which is
     * written regardless and always has been). Derived from <em>declarations</em> rather than from whatever
     * happens to be in a table, so the answer does not depend on how much of the corpus has been embedded
     * yet.
     *
     * <p><b>TYPE placement only.</b> Unlike {@link #concatenate()}, this has no per-field meaning -- a
     * summary row is the container's, and a field cannot opt half of one in. It is refused on a field at
     * registration rather than ignored.
     *
     * <p><b>Defaults to {@code false}, and off is the pre-OMI-458 behaviour exactly.</b> Nothing is
     * provisioned, nothing extra is written, and no queue row is enqueued for a supplied external vector.
     * A {@code nearestBySummary()} against a non-ambient model still <em>answers</em> when this is off --
     * it folds the candidates in memory rather than reading an index, which is slower by a factor of the
     * corpus but is not a different answer. See {@code doc/spec/persistence-bridge.md}.
     *
     * <p>⚠️ <b>It costs a write per model on every recomputation of this container, and a queue row per
     * external vector supplied anywhere beneath it.</b> That is the trade being opted into: a ranking that
     * was O(containers x their members) per query becomes an indexed lookup, paid for at write time. A
     * container nobody ranks this way should leave it off.
     */
    boolean persistModelSummaries() default false;
}
