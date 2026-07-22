package dev.xtrafe.javai.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Ordinary Spring-Data-style derived finders over a non-vectorized entity (OMI-138), exercised against all
 * three backends. Covers the operator vocabulary (equality, comparison, {@code Between}, {@code Containing},
 * {@code In}, {@code IgnoreCase}, boolean {@code True}), the projection kinds ({@code count}/{@code exists}/
 * {@code delete}), the result adapters ({@code List}/{@code Optional}/single/{@code Page}), static
 * {@code OrderBy} + {@code First}, and dynamic {@link Sort}/{@link Pageable}. Nested-association finders
 * live in {@link TestAccountNestedRepository} instead, since they aren't supported on every backend.
 */
interface TestAccountRepository extends JavAIRepository<TestAccount> {

    List<TestAccount> findByUsername(String username);

    Optional<TestAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByActive(boolean active);

    List<TestAccount> findByActiveTrue();

    List<TestAccount> findByAgeGreaterThanEqual(int age);

    List<TestAccount> findByAgeBetween(int lowInclusive, int highInclusive);

    List<TestAccount> findByUsernameIgnoreCase(String username);

    List<TestAccount> findByUsernameContainingOrderByUsernameAsc(String fragment);

    List<TestAccount> findByUsernameInOrderByUsernameAsc(Collection<String> usernames);

    List<TestAccount> findAllByOrderByAgeAsc();

    TestAccount findFirstByOrderByAgeDesc();

    Page<TestAccount> findByAgeGreaterThanEqual(int age, Pageable pageable);

    List<TestAccount> findByAgeGreaterThanEqual(int age, Sort sort);

    long deleteByUsername(String username);
}
