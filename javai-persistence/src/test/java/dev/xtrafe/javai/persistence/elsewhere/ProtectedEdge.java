package dev.xtrafe.javai.persistence.elsewhere;

import dev.xtrafe.javai.collections.JavAIEdge;

/** A package-private record edge, so its canonical constructor is not public either (OMI-607). */
record ProtectedEdge(String reason) implements JavAIEdge {
}
