package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * OMI-161: a {@code @JavAIVectorizable} entity with a <b>lazy</b> singular association to another
 * {@code @JavAIVectorizable} entity used to fail its save with
 * {@code null value in column "owner_id" of relation "javai_vectors__..." violates not-null constraint}.
 *
 * <p><b>What actually triggers it</b>, isolated here rather than assumed -- the field reflection this
 * backend uses to find related entities cannot tell a Hibernate proxy from the entity it stands for.
 * {@code save()} calls {@code session.merge(entity)}, and the merged copy holds an <em>uninitialized
 * proxy</em> for a lazy singular association instead of loading the target. That proxy still satisfies
 * {@code instanceof JavAIVectorizable} (it subclasses the real entity), so the related-entity walk tried to
 * write vectors for it -- but a proxy's {@code @Id} field is never populated (it delegates through an
 * interceptor), so reading it reflectively gave {@code null}, and {@code getClass()} gave
 * {@code TestAssocTarget$HibernateProxy$...} rather than {@code TestAssocTarget}, which would have been the
 * wrong {@code owner_type} even had the id been right.
 *
 * <p><b>Why it hid for so long, and why these two fixtures are a matched pair.</b> The only difference
 * between {@link TestLazyAssocOwner} and {@link TestEagerAssocOwner} is {@code FetchType}. Every singular
 * association that already existed in this project -- its own fixtures and JavAI's shipped
 * {@code Tag -> TagSet} alike -- is eager, so the walk always happened to see a real, initialized instance.
 * Neither fixture is woven (both are hand-written stand-ins, see {@link TestArticle}), which rules out
 * load-time versus build-time weaving as the variable: the same failure reproduces with no weaver in the
 * picture at all.
 */
@Testcontainers
class SingularAssociationVectorTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static TestAssocTargetRepository targetRepository;
    private static TestLazyAssocOwnerRepository lazyRepository;
    private static TestEagerAssocOwnerRepository eagerRepository;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        JavAIPersistenceConfig config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        targetRepository = JavAIPI.repository(TestAssocTargetRepository.class, config);
        lazyRepository = JavAIPI.repository(TestLazyAssocOwnerRepository.class, config);
        eagerRepository = JavAIPI.repository(TestEagerAssocOwnerRepository.class, config);
    }

    /** The exact reported failure: saving an owner whose lazy association points at an already-persisted
     *  vectorizable. Before the fix this threw a NOT NULL violation on {@code owner_id}. */
    @Test
    void lazySingularAssociationToAnotherVectorizableSaves() {
        TestAssocTarget target = targetRepository.save(new TestAssocTarget("lazy target"));
        TestLazyAssocOwner owner = lazyRepository.save(new TestLazyAssocOwner("lazy owner", target));

        assertNotNull(owner.getId());
        assertVectorRow(owner.getId(), TestLazyAssocOwner.class);
        assertVectorRow(target.getId(), TestAssocTarget.class);
    }

    /** The control. Identical but eager -- passed before the fix too, and must keep passing after it. */
    @Test
    void eagerSingularAssociationToAnotherVectorizableSaves() {
        TestAssocTarget target = targetRepository.save(new TestAssocTarget("eager target"));
        TestEagerAssocOwner owner = eagerRepository.save(new TestEagerAssocOwner("eager owner", target));

        assertNotNull(owner.getId());
        assertVectorRow(owner.getId(), TestEagerAssocOwner.class);
        assertVectorRow(target.getId(), TestAssocTarget.class);
    }

    /** Re-saving an owner loaded back from the database is the other way to hold a proxy in that field --
     *  {@code findById} leaves the lazy association uninitialized just as {@code merge} does. */
    @Test
    void resavingALoadedOwnerWithAnUninitializedProxySaves() {
        TestAssocTarget target = targetRepository.save(new TestAssocTarget("reload target"));
        UUID ownerId = lazyRepository.save(new TestLazyAssocOwner("reload owner", target)).getId();

        TestLazyAssocOwner reloaded = lazyRepository.findById(ownerId).orElseThrow();
        reloaded.setLabel("reload owner, mutated");
        lazyRepository.save(reloaded);

        assertVectorRow(ownerId, TestLazyAssocOwner.class);
        assertVectorRow(target.getId(), TestAssocTarget.class);
    }

    /** Structural, not just "it didn't throw": the target's vector row must be keyed by the *entity*
     *  class, never the proxy's generated subclass name, or nothing would ever find it again. */
    @Test
    void targetVectorRowIsKeyedByTheEntityClassNotTheProxyClass() {
        TestAssocTarget target = targetRepository.save(new TestAssocTarget("owner type target"));
        lazyRepository.save(new TestLazyAssocOwner("owner type owner", target));

        assertEquals(0, countOwnerTypesMatching("%HibernateProxy%"),
                "no vector row may be keyed by a Hibernate proxy class name");
        assertEquals(TestAssocTarget.class.getName(), ownerTypeOf(target.getId()));
    }

    private static void assertVectorRow(UUID ownerId, Class<?> ownerType) {
        assertEquals(1, countVectorRows(ownerId, ownerType, "label"),
                "expected exactly one 'label' vector row for " + ownerType.getSimpleName() + " " + ownerId);
    }

    private static int countVectorRows(UUID ownerId, Class<?> ownerType, String fieldName) {
        String table = "javai_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table
                     + " WHERE owner_id = ? AND owner_type = ? AND field_name = ?")) {
            statement.setObject(1, ownerId);
            statement.setString(2, ownerType.getName());
            statement.setString(3, fieldName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int countOwnerTypesMatching(String pattern) {
        String table = "javai_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE owner_type LIKE ?")) {
            statement.setString(1, pattern);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ownerTypeOf(UUID ownerId) {
        String table = "javai_vectors__" + ModelIds.sanitize(FakeEmbeddingProvider.MODEL_ID);
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT DISTINCT owner_type FROM " + table + " WHERE owner_id = ?")) {
            statement.setObject(1, ownerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
