package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Indexes a {@code String} field for ranked full-text search (OMI-642): term and phrase matching with stemming in
 * {@link #language()}, searched through {@code JavAIRepository.matching(field)}, and alone or fused with a vector
 * search through {@code NearestQuery.alsoMatching(field, text)}. With {@link #fuzzy()}, also by trigram similarity, for
 * names written several ways or damaged by OCR. See doc/spec/persistence-bridge.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FullText {

    /** The text-search configuration the field is stemmed in, e.g. {@code "english"}, {@code "simple"}. */
    String language() default "english";

    /** Whether the field is also indexed for trigram similarity -- {@code TextQuery.fuzzy()}. */
    boolean fuzzy() default false;
}
