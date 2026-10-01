package dev.xtrafe.javai.persistence;

/**
 * The repository the Spring transaction-conformance suites drive.
 *
 * <p>Top-level rather than nested inside one test class, because the same services
 * ({@link OuterService}, {@link InnerService}, {@link ClassLevelService}) are wired into <em>both</em>
 * supported topologies -- the factory Spring builds and the factory JavAI builds -- and neither suite may
 * look like the other's guest (OMI-275).
 */
interface SpringTxRecordRepository extends JavAIRepository<TestTxRecord> {
}
