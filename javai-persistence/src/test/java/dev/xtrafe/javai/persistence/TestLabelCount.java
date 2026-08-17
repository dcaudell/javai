package dev.xtrafe.javai.persistence;

/**
 * The shape a grouped aggregate comes back as -- the parent ticket's "so a grouped aggregate can come back as
 * {@code (id, count)} pairs rather than entities".
 *
 * <p>A plain record, targeted by an ordinary JPQL constructor expression. Nothing in JavAI maps it: Hibernate
 * 7 instantiates records from a {@code select new …} directly, which is why this needed no projection
 * machinery of its own.
 */
record TestLabelCount(String label, long count) {
}
