package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.Taggregate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a type opts into the concatenated tag-text vector -- {@code @Taggregate(concatenate = true)} at
 * TYPE placement, the only placement where that flag means anything.
 *
 * <p>⚠️ <b>This class used to walk {@code @Taggregate} fields too, and no longer does (OMI-304.)</b> That
 * walk was a second implementation of "which members does this container hold", answered from a loaded
 * object graph rather than from the database -- so it required the caller to hold an open session and threw
 * {@code LazyInitializationException} on the lazy {@code @ManyToMany} collections that are the normal shape
 * for a container. Membership is now read from the mapping through {@code Containment}, once, for every
 * backend. Deleted rather than left unused: an unused second answer to a question that already has one is
 * how the two drift apart.
 */
final class TaggregateReflection {

    private static final Map<Class<?>, Boolean> CONCATENATES = new ConcurrentHashMap<>();

    private TaggregateReflection() {
    }

    /** Whether {@code type} carries {@code @Taggregate(concatenate = true)} -- cached, since the
     *  declaration is static and this is read on every derived-vector recompute. */
    static boolean concatenates(Class<?> type) {
        return CONCATENATES.computeIfAbsent(type, t -> {
            Taggregate declared = t.getAnnotation(Taggregate.class);
            return declared != null && declared.concatenate();
        });
    }
}
