package dev.xtrafe.javai.persistence;

/** Realized once via {@code JavAIPI.repository(TestTagRepository.class)} purely to register
 *  {@link TestTag}'s node label before {@link TestArticleWithTags#getTagsByCode()} traversal needs to
 *  resolve it -- {@code RepositoryBackendNeo4j.registerEntityType} doesn't (yet) recursively discover
 *  related types the way {@code RepositoryBackendHibernatePostgres} does. */
interface TestTagRepository extends JavAIRepository<TestTag> {
}
