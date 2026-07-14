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
}
