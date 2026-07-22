package dev.xtrafe.javai.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared, backend-agnostic assertions for the ordinary Spring-Data-style derived finders added in OMI-138,
 * run identically against all three backend test classes so each proves the same relational-finder behavior
 * over the same non-vectorized {@link TestAccount} fixture. Keeping the assertions here (rather than copied
 * into each {@code RepositoryBackend*Test}) is the whole point: "the same finders behave the same way on
 * Postgres, Neo4j, and Mongo" is exactly what's being proven.
 */
final class DerivedFinderTestSupport {

    private DerivedFinderTestSupport() {
    }

    /** Clears any existing accounts and inserts the canonical four the assertions below are written against.
     *  Idempotent, so each test can call it to isolate itself from a shared container's leftover state. */
    static void seed(TestAccountRepository repository) {
        for (TestAccount existing : repository.findAll()) {
            repository.deleteById(existing.getId());
        }
        repository.save(new TestAccount("alice", "alice@example.com", 30, true, new TestProfile("ace", "Portland")));
        repository.save(new TestAccount("bob", "bob@example.com", 25, false, new TestProfile("beacon", "Seattle")));
        repository.save(new TestAccount("carol", "carol@example.com", 40, true, new TestProfile("cadence", "Portland")));
        repository.save(new TestAccount("dave", "dave@example.com", 25, true, new TestProfile("delta", "Denver")));
    }

    /** Exercises the operator vocabulary, projections, result adapters, static ordering/limiting, and dynamic
     *  Sort/Pageable -- everything that doesn't require a related-entity traversal. Assumes {@link #seed} ran. */
    static void assertSimpleDerivedFinders(TestAccountRepository repository) {
        // Equality + single-property List.
        List<TestAccount> alice = repository.findByUsername("alice");
        assertEquals(1, alice.size());
        assertEquals(30, alice.get(0).getAge());
        assertEquals("alice@example.com", alice.get(0).getEmail());
        assertTrue(repository.findByUsername("nobody").isEmpty());

        // Optional single result.
        Optional<TestAccount> bob = repository.findByEmail("bob@example.com");
        assertTrue(bob.isPresent());
        assertEquals("bob", bob.get().getUsername());
        assertTrue(repository.findByEmail("nope@example.com").isEmpty());

        // exists + count projections.
        assertTrue(repository.existsByEmail("carol@example.com"));
        assertFalse(repository.existsByEmail("ghost@example.com"));
        assertEquals(3, repository.countByActive(true));
        assertEquals(1, repository.countByActive(false));

        // boolean True keyword, comparison, Between.
        assertEquals(3, repository.findByActiveTrue().size());
        assertEquals(usernames("alice", "carol"), sortedUsernames(repository.findByAgeGreaterThanEqual(30)));
        assertEquals(usernames("alice", "bob", "dave"), sortedUsernames(repository.findByAgeBetween(25, 30)));

        // IgnoreCase + Containing with static OrderBy + In with static OrderBy.
        List<TestAccount> ignoreCase = repository.findByUsernameIgnoreCase("ALICE");
        assertEquals(1, ignoreCase.size());
        assertEquals("alice", ignoreCase.get(0).getUsername());
        assertEquals(List.of("alice", "carol", "dave"),
                repository.findByUsernameContainingOrderByUsernameAsc("a").stream().map(TestAccount::getUsername).toList());
        assertEquals(List.of("alice", "carol"),
                repository.findByUsernameInOrderByUsernameAsc(List.of("carol", "alice")).stream()
                        .map(TestAccount::getUsername).toList());

        // Predicate-less finder with static OrderBy, and First + OrderBy single-result.
        List<TestAccount> byAgeAsc = repository.findAllByOrderByAgeAsc();
        assertEquals(4, byAgeAsc.size());
        assertEquals(25, byAgeAsc.get(0).getAge());
        assertEquals("carol", byAgeAsc.get(3).getUsername());
        TestAccount oldest = repository.findFirstByOrderByAgeDesc();
        assertNotNull(oldest);
        assertEquals("carol", oldest.getUsername());

        // Dynamic Pageable (with a total-count-backed Page) and dynamic Sort.
        Page<TestAccount> firstPage =
                repository.findByAgeGreaterThanEqual(25, PageRequest.of(0, 2, Sort.by("username")));
        assertEquals(4, firstPage.getTotalElements());
        assertEquals(List.of("alice", "bob"),
                firstPage.getContent().stream().map(TestAccount::getUsername).toList());
        List<TestAccount> byAgeDesc = repository.findByAgeGreaterThanEqual(25, Sort.by(Sort.Direction.DESC, "age"));
        assertEquals("carol", byAgeDesc.get(0).getUsername());

        // Delete projection -- mutates the seed, so this is last.
        assertEquals(1, repository.deleteByUsername("dave"));
        assertTrue(repository.findByUsername("dave").isEmpty());
        assertEquals(2, repository.countByActive(true));
    }

    /** Nested-association finders -- filtering an account by a field of its singular related profile. Only
     *  Postgres and Neo4j support these in this pass. Assumes {@link #seed} ran immediately before. */
    static void assertNestedDerivedFinders(TestAccountNestedRepository repository) {
        List<TestAccount> byHandle = repository.findByProfileHandle("ace");
        assertEquals(1, byHandle.size());
        assertEquals("alice", byHandle.get(0).getUsername());
        assertTrue(repository.findByProfileHandle("no-such-handle").isEmpty());

        assertEquals(List.of("alice", "carol"),
                repository.findByProfileCityOrderByUsernameAsc("Portland").stream()
                        .map(TestAccount::getUsername).toList());
    }

    private static List<String> usernames(String... names) {
        return List.of(names);
    }

    private static List<String> sortedUsernames(List<TestAccount> accounts) {
        return accounts.stream().map(TestAccount::getUsername).sorted().toList();
    }
}
