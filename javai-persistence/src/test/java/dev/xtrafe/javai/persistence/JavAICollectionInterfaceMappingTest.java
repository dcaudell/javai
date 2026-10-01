package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one supported JavAI collection shape, proven for all three interfaces (OMI-277).
 *
 * <p>OMI-277 refused the concrete-typed field, which makes "declare it by the interface, non-final, with the
 * ordinary JPA annotation" the only way to have a JavAI collection on an entity. That rule was previously
 * advice with one worked example: every fixture and consumer in this project used {@code JavAIList}, so
 * {@code JavAISet} and {@code JavAIMap} were supported in the mapping code and demonstrated nowhere.
 * Refusing the alternative makes proving them part of the price.
 *
 * <p>Each is checked for the four things that distinguish this mapping from a plain JPA one: Hibernate
 * substitutes JavAI's own persistent collection (so vectors and dirty-tracking survive), the association is
 * lazy, it round-trips exactly once, and it is reachable through another association rather than only as the
 * root of a repository call.
 */
@Testcontainers
class JavAICollectionInterfaceMappingTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestCatalogueRepository catalogues;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        catalogues = JavAIPI.repository(TestCatalogueRepository.class, config);
        JavAIPI.repository(TestBookRepository.class, config);
    }

    @Test
    void allThreeInterfacesRoundTripExactlyTheirOwnMembers() {
        UUID id = savedCatalogue().getId();

        JavAIPI.inTransaction(config, () -> {
            TestCatalogue loaded = catalogues.findById(id).orElseThrow();
            assertEquals(List.of("ordered-one", "ordered-two"),
                    loaded.getOrdered().stream().map(TestBook::getTitle).toList());
            assertEquals(1, loaded.getUnique().size(), "a set maps as a set, not doubled");
            assertEquals("mapped-book", loaded.getByCode().get("A1").getTitle(),
                    "a map round-trips its keys, not just its values");
            return null;
        });
    }

    /** Hibernate must substitute JavAI's own collection, or the field is a plain JPA one wearing the name. */
    @Test
    void hibernateSubstitutesJavAIsOwnPersistentCollectionForEachInterface() {
        UUID id = savedCatalogue().getId();

        JavAIPI.inTransaction(config, () -> {
            TestCatalogue loaded = catalogues.findById(id).orElseThrow();
            Hibernate.initialize(loaded.getOrdered());
            Hibernate.initialize(loaded.getUnique());
            Hibernate.initialize(loaded.getByCode());

            assertInstanceOf(PersistentJavAIList.class, loaded.getOrdered());
            assertInstanceOf(PersistentJavAISet.class, loaded.getUnique());
            assertInstanceOf(PersistentJavAIMap.class, loaded.getByCode());
            assertTrue(loaded.getOrdered().centroid().dims() > 0,
                    "the JavAI behaviour must work on the instance Hibernate substituted");
            return null;
        });
    }

    /** All three are ordinary lazy associations -- the property the refused mapping could never have. */
    @Test
    void allThreeAreLazyLikeAnyOtherAssociation() {
        UUID id = savedCatalogue().getId();

        TestCatalogue detached = catalogues.findById(id).orElseThrow();

        assertFalse(Hibernate.isInitialized(detached.getOrdered()), "JavAIList is lazy");
        assertFalse(Hibernate.isInitialized(detached.getUnique()), "JavAISet is lazy");
        assertFalse(Hibernate.isInitialized(detached.getByCode()), "JavAIMap is lazy");
    }

    private static TestCatalogue savedCatalogue() {
        TestCatalogue catalogue = new TestCatalogue("catalogue-" + UUID.randomUUID());
        catalogue.getOrdered().add(new TestBook("ordered-one"));
        catalogue.getOrdered().add(new TestBook("ordered-two"));
        catalogue.getUnique().add(new TestBook("unique-book"));
        catalogue.getByCode().put("A1", new TestBook("mapped-book"));
        catalogues.save(catalogue);
        return catalogue;
    }
}
