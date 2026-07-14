package dev.xtrafe.javai.vector;

import java.lang.ref.WeakReference;

/**
 * A weakly-held reference compared by identity, not {@code equals()}. Backs every
 * {@code dependents()} set in this module -- {@link java.util.WeakHashMap} would work too, but
 * compares its weak keys with {@code equals()}/{@code hashCode()}, which breaks the moment a
 * dependent's {@code equals()} is based on its own {@code @Vectorize} fields (exactly the fields this
 * system mutates). Dependency edges must be identity-based regardless of how a domain class defines
 * equality.
 */
final class IdentityWeakReference extends WeakReference<Object> {

    private final int identityHash;

    IdentityWeakReference(Object referent) {
        super(referent);
        this.identityHash = System.identityHashCode(referent);
    }

    @Override
    public int hashCode() {
        return identityHash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof IdentityWeakReference other)) {
            return false;
        }
        Object thisReferent = get();
        Object otherReferent = other.get();
        return thisReferent != null && thisReferent == otherReferent;
    }
}
