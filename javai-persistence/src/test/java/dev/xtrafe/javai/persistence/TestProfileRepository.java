package dev.xtrafe.javai.persistence;

/** Realized once via {@code JavAIPI.repository(TestProfileRepository.class)} purely to register
 *  {@link TestProfile}'s node label before {@link TestAccount#getProfile()} relationship traversal needs
 *  to resolve it on the Neo4j backend -- {@code RepositoryBackendNeo4j.registerEntityType} doesn't (yet)
 *  recursively discover related types the way the Postgres/Mongo backends do (same reason
 *  {@link TestTagRepository} exists). */
interface TestProfileRepository extends JavAIRepository<TestProfile> {
}
