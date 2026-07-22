package dev.xtrafe.javai.persistence;

import java.util.List;

/**
 * Deliberately invalid: {@code nonexistentColumn} is not a property of {@link TestAccount}, so PartTree's
 * own property resolution rejects it -- proving an unknown-property derived finder fails fast, at
 * repository-creation time, with a clear message, exactly like an invalid {@code findNearestBy*} does.
 * Backend-agnostic (the parse happens before any backend feasibility check).
 */
interface TestBadPropertyRepository extends JavAIRepository<TestAccount> {

    List<TestAccount> findByNonexistentColumn(String value);
}
