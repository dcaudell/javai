package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Marks a field's or class's participation in <b>Taggregate</b> -- derived taggings for containers, and the
 * concatenated tag-text vector (OMI-302). Mirrors {@link Summary}'s grammar exactly: same targets, same
 * {@code concatenate} vocabulary, the same "the owning field, never the child, decides what flows up" rule.
 * See doc/spec/tagging.md's "Taggregate: derived taggings for containers".
 *
 * <table border="1">
 * <caption>What this means where you put it</caption>
 * <tr><th>Placement</th><th>Meaning</th></tr>
 * <tr><td>{@code @Taggregate(concatenate = true)} on a <b>TYPE</b></td>
 *     <td>This class produces a <b>tag-text vector</b> from its own taggings -- including any aggregate
 *         rows its fields absorbed.</td></tr>
 * <tr><td>On a <b>FIELD</b> referencing a {@code Taggable}</td>
 *     <td>Absorb that child's taggings into the declaring object's aggregate.</td></tr>
 * <tr><td>On a <b>FIELD</b> holding a JavAI collection of {@code Taggable}s</td>
 *     <td>Aggregate the members' taggings into the declaring object's aggregate.</td></tr>
 * </table>
 *
 * <p><b>This is a tagging capability, not a Vector Core one</b> -- a completely separate lineage from
 * {@link JavAIVectorizable}. Nothing about it is woven and nothing about it requires
 * {@code @JavAIVectorizable}, on the container or on the members: participation requires exactly what
 * {@code Taggable} requires -- the marker interface and an {@code @jakarta.persistence.Id UUID}, read
 * reflectively. The aggregate is maintained by {@code JavAITagRepository} as ordinary {@code Tagging} rows
 * with {@code source = "aggregate"}, and the tag-text vector is served by repository methods
 * ({@code tagText}/{@code tagTextVector}/{@code tagTextIndex}), never by a woven accessor. Members may be
 * woven, unwoven, vectorizable or not, freely mixed.
 *
 * <p>A field declared on a {@code @MappedSuperclass} applies to every subclass -- the reflective hierarchy
 * walk supports that natively; the weaver constraint that forced {@link ExternalVector} to type level does
 * not exist in this lineage.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface Taggregate {

    /**
     * Opts the class into the <b>concatenated tag-text vector</b>: its tags rendered as one deterministic
     * string (display names, affinity-descending then slug, top-K capped) and embedded, so "what this is
     * tagged as" becomes searchable as language. Same word as {@link Summary#concatenate} for the same
     * idea. A non-aggregating class (its taggings all applied directly) uses the TYPE placement alone, with
     * no fields marked.
     *
     * <p><b>Meaningful only at TYPE placement</b> -- one deliberate asymmetry against {@code @Summary},
     * documented in doc/spec/tagging.md: {@code @Summary} needs a field-level {@code concatenate} because
     * text is a second channel alongside the vector fold, whereas a {@code @Taggregate} field already
     * propagates the taggings themselves as rows, and the container's tag-text renders from those rows --
     * there is no separate text channel to absorb. On a FIELD placement this flag is ignored.
     */
    boolean concatenate() default false;
}
