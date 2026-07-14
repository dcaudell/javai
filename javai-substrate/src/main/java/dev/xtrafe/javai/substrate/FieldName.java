package dev.xtrafe.javai.substrate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the {@code fieldName} parameter of {@link VectorizeFieldSetterAdvice#onExit} so
 * {@code Advice.withCustomMapping().bind(FieldName.class, fieldName)} can inject the actual
 * {@code @Vectorize} field name as a real constant, per setter, at weave time -- the same
 * per-call-site-constant idea {@code MethodCall.with(...)} already uses for {@code vector()}'s
 * {@code vectorizeFieldsCsv}, just via {@code Advice}'s own binding mechanism instead, since a single shared
 * {@code Advice} class (inlined into every {@code @Vectorize} field's setter alike) has no other way to
 * know which specific field it's attached to.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface FieldName {
}
