package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Search-semantic visibility -- object semantics, independent of Java access modifiers.
 * See doc/spec/vector-core.md.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface SearchVisibility {

    Visibility value();

    enum Visibility {
        PUBLIC,
        PROTECTED,
        PRIVATE
    }
}
