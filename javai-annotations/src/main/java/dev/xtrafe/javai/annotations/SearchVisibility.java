package dev.xtrafe.javai.annotations;

import java.lang.annotation.*;

/**
 * Search-semantic visibility -- object semantics, independent of Java access modifiers. {@code PRIVATE}
 * on a field blocks {@code query()} from traversing through it at all; {@code PRIVATE} on a class blocks
 * instances of it from ever being returned as a {@code query()} match (but not from being traversed
 * through, so a deliberately "invisible" pass-through node's own descendants stay reachable). {@code
 * PUBLIC} and {@code PROTECTED} currently behave identically -- the finer distinction is reserved for
 * Completion Fabric's {@code toContext()} serialization, not built yet. See doc/spec/vector-core.md and
 * {@code JavAIRuntime}'s query() implementation.
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
