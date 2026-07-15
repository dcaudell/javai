package dev.xtrafe.javai.tagging;

/**
 * {@link TaggableRef#taggableType()} is always a fully-qualified class name (collision-safety: this very
 * codebase has two distinct classes both simply named {@code Tag} in different packages -- see
 * {@code e2e-client-test}'s own domain package). Neo4j's node labels and MongoDB's collection names, by
 * established convention elsewhere in {@code javai-persistence}, are the simple name instead -- this class
 * bridges the two, only where a backend genuinely needs to address a node label/collection rather than
 * store/compare a {@link TaggableRef}.
 */
final class TypeNames {

    private TypeNames() {
    }

    static String simpleNameOf(String fullyQualifiedName) {
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot < 0 ? fullyQualifiedName : fullyQualifiedName.substring(lastDot + 1);
    }
}
