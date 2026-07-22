package dev.xtrafe.javai.persistence;

import java.util.List;

/**
 * Nested-association derived finders -- filtering an entity by a field of its singular related entity.
 * Supported on Postgres (Criteria auto-joins the {@code @OneToOne}) and Neo4j (a relationship traversal),
 * per OMI-138's first pass. Deliberately a separate interface from {@link TestAccountRepository}: the
 * MongoDB backend rejects nested paths at repository-creation time (references are {@code {type, id}}
 * pointers, not embedded), so {@code JavAIPI.repository(TestAccountNestedRepository.class, mongoConfig)}
 * is expected to throw -- see {@code RepositoryBackendSpringDataMongoTest}.
 */
interface TestAccountNestedRepository extends JavAIRepository<TestAccount> {

    List<TestAccount> findByProfileHandle(String handle);

    List<TestAccount> findByProfileCityOrderByUsernameAsc(String city);
}
