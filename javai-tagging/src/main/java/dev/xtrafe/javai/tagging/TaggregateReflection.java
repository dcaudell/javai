package dev.xtrafe.javai.tagging;

import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.persistence.PersistentEntities;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-class read of a type's {@code @Taggregate} declaration -- which fields contribute members to its
 * aggregate (FIELD placements, hierarchy-walked so a {@code @MappedSuperclass} declaration applies to every
 * subclass), and whether the type opts into the tag-text vector (TYPE placement with
 * {@code concatenate = true}, read via {@code getAnnotation} on the class itself, exactly as
 * {@code JavAIRuntime} reads {@code @Summary}'s own TYPE placement). Cached per class -- the declaration is
 * static, and the walk runs on every read-path drift check.
 *
 * <p>Companion to {@link TaggingReflection}, not a second identity mechanism: member identity always goes
 * through {@link TaggingReflection#idOf} (and {@link PersistentEntities#resolve} first, for the same
 * unresolved-proxy reasons {@code JavAITagRepository.refOf} documents).
 */
final class TaggregateReflection {

    private record Declaration(List<Field> memberFields, boolean concatenate) {
    }

    private static final Map<Class<?>, Declaration> DECLARATIONS = new ConcurrentHashMap<>();

    private TaggregateReflection() {
    }

    /** Whether {@code type} declares any {@code @Taggregate} member fields -- i.e. is an aggregate. */
    static boolean isAggregate(Class<?> type) {
        return !declarationOf(type).memberFields().isEmpty();
    }

    /** Whether {@code type} opts into the tag-text vector ({@code @Taggregate(concatenate = true)} at TYPE
     *  placement -- the only placement where the flag means anything; see the annotation's own javadoc). */
    static boolean concatenates(Class<?> type) {
        return declarationOf(type).concatenate();
    }

    /**
     * The current member refs of {@code container}'s {@code @Taggregate} fields, in field-then-iteration
     * order, duplicates collapsed. Each member is proxy-resolved before its ref is built. A {@code null}
     * field or {@code null} element contributes nothing; a populated field whose value is neither a
     * {@code Taggable} nor a {@code Collection} of them is refused loudly rather than silently skipped.
     */
    static List<TaggableRef> memberRefsOf(Object container) {
        Set<TaggableRef> members = new LinkedHashSet<>();
        for (Field field : declarationOf(container.getClass()).memberFields()) {
            Object value;
            try {
                value = field.get(container);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read @Taggregate field " + field, e);
            }
            if (value == null) {
                continue;
            }
            if (value instanceof Collection<?> collection) {
                for (Object element : collection) {
                    if (element != null) {
                        members.add(refOfMember(field, element));
                    }
                }
            } else {
                members.add(refOfMember(field, value));
            }
        }
        return List.copyOf(members);
    }

    private static TaggableRef refOfMember(Field field, Object member) {
        Object resolved = PersistentEntities.resolve(member);
        if (!(resolved instanceof Taggable)) {
            throw new IllegalArgumentException("@Taggregate field " + field + " holds a "
                    + resolved.getClass().getName() + ", which does not implement the Taggable marker"
                    + " interface -- Taggregate membership requires exactly what tagging requires:"
                    + " implements Taggable and an @Id UUID.");
        }
        return new TaggableRef(resolved.getClass().getName(), TaggingReflection.idOf(resolved));
    }

    private static Declaration declarationOf(Class<?> type) {
        return DECLARATIONS.computeIfAbsent(type, TaggregateReflection::readDeclaration);
    }

    private static Declaration readDeclaration(Class<?> type) {
        Taggregate typeLevel = type.getAnnotation(Taggregate.class);
        boolean concatenate = typeLevel != null && typeLevel.concatenate();
        List<Field> memberFields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Taggregate.class)) {
                    field.setAccessible(true);
                    memberFields.add(field);
                }
            }
        }
        return new Declaration(List.copyOf(memberFields), concatenate);
    }
}
