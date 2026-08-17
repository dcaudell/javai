package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAIRuntime;
import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.testsupport.FakeEmbeddingProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OMI-407: predicates over an {@code @Any} association, with no shadow read-only column mappings.
 *
 * <p>The workaround this removes was to map the discriminator and the foreign key a <em>second</em> time as
 * plain read-only columns purely so a derived finder could see them -- duplication that every queryable
 * {@code @Any} in a codebase would repeat, with two mappings free to drift apart.
 *
 * <p>What made it look necessary is that an {@code @Any} genuinely cannot be <em>joined</em> through, which
 * had been taken to mean it could not be <em>filtered</em> on either. It can: Hibernate resolves
 * {@code Path.type()} straight to the discriminator column, and ordinary equality straight to the pair. The
 * refusals below are the cases where the original reasoning does hold, kept and made specific rather than
 * inherited from the generic "not a singular @Entity" message.
 */
@Testcontainers
class AnyPredicateTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JavAIPersistenceConfig config;
    private static TestAlbumQueryRepository albums;

    /** Distinct titles per test, so each can identify its own rows in a shared, never-truncated table. */
    private String tag;

    @BeforeAll
    static void configure() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        config = JavAIPersistenceConfig.builder()
                .backend(JavAIPersistenceConfig.Backend.POSTGRES)
                .postgresUrl(postgres.getJdbcUrl())
                .postgresUsername(postgres.getUsername())
                .postgresPassword(postgres.getPassword())
                .build();
        albums = JavAIPI.repository(TestAlbumQueryRepository.class, config);
    }

    @BeforeEach
    void freshTag() {
        JavAIRuntime.configureEmbeddingProvider(new FakeEmbeddingProvider());
        tag = "t" + UUID.randomUUID().toString().substring(0, 8);
    }

    private TestAlbum saveImageBacked(String suffix) {
        return albums.save(new TestAlbum(tag + " " + suffix, new TestImageCover(tag + " image " + suffix)));
    }

    private TestAlbum saveLottieBacked(String suffix) {
        return albums.save(new TestAlbum(tag + " " + suffix, new TestLottieCover(tag + " lottie " + suffix)));
    }

    private Set<UUID> idsOf(List<TestAlbum> found) {
        return found.stream().map(TestAlbum::getId).collect(Collectors.toSet());
    }

    /** Only this test's own rows, so a shared table cannot make an assertion pass or fail by accident. */
    private Set<UUID> mine(List<TestAlbum> found) {
        return found.stream()
                .filter(album -> album.getTitle() != null && album.getTitle().startsWith(tag))
                .map(TestAlbum::getId)
                .collect(Collectors.toSet());
    }

    // ---- by target instance ------------------------------------------------------------------------

    @Test
    void findsByTheTargetInstanceItself() {
        TestAlbum imageBacked = saveImageBacked("a");
        saveLottieBacked("b");

        TestCover cover = albums.findById(imageBacked.getId()).orElseThrow().getCover();

        assertEquals(Set.of(imageBacked.getId()), idsOf(albums.findByCover(cover)),
                "equality on an @Any resolves to its discriminator + key pair, no shadow mapping involved");
    }

    @Test
    void findsByTheTargetInstanceNegated() {
        TestAlbum imageBacked = saveImageBacked("a");
        TestAlbum lottieBacked = saveLottieBacked("b");

        TestCover cover = albums.findById(imageBacked.getId()).orElseThrow().getCover();

        assertTrue(mine(albums.findByCoverNot(cover)).contains(lottieBacked.getId()));
        assertFalse(mine(albums.findByCoverNot(cover)).contains(imageBacked.getId()));
    }

    @Test
    void findsByAnyOfSeveralTargetInstancesAcrossDifferentTargetTypes() {
        TestAlbum imageBacked = saveImageBacked("a");
        TestAlbum lottieBacked = saveLottieBacked("b");
        saveImageBacked("c");

        TestCover image = albums.findById(imageBacked.getId()).orElseThrow().getCover();
        TestCover lottie = albums.findById(lottieBacked.getId()).orElseThrow().getCover();

        assertEquals(Set.of(imageBacked.getId(), lottieBacked.getId()),
                idsOf(albums.findByCoverIn(List.of(image, lottie))),
                "an In over an @Any spans target types -- which is the whole point of the association");
    }

    @Test
    void findsRowsWhoseTargetIsUnset() {
        TestAlbum coverless = albums.save(new TestAlbum(tag + " coverless", null));
        TestAlbum covered = saveImageBacked("a");

        assertTrue(mine(albums.findByCoverIsNull()).contains(coverless.getId()));
        assertFalse(mine(albums.findByCoverIsNull()).contains(covered.getId()));
        assertTrue(mine(albums.findByCoverIsNotNull()).contains(covered.getId()));
        assertFalse(mine(albums.findByCoverIsNotNull()).contains(coverless.getId()));
    }

    // ---- by target type: the OfType keyword --------------------------------------------------------

    @Test
    void findsEveryRowWhoseTargetIsOfOneType() {
        TestAlbum first = saveImageBacked("a");
        TestAlbum second = saveImageBacked("b");
        TestAlbum other = saveLottieBacked("c");

        Set<UUID> imageBacked = mine(albums.findByCoverOfType(TestImageCover.class));

        assertEquals(Set.of(first.getId(), second.getId()), imageBacked,
                "the question the shadow discriminator column existed to answer");
        assertFalse(imageBacked.contains(other.getId()));
    }

    @Test
    void aNullTargetIsNotOfAnyType() {
        albums.save(new TestAlbum(tag + " coverless", null));
        TestAlbum imageBacked = saveImageBacked("a");

        assertEquals(Set.of(imageBacked.getId()), mine(albums.findByCoverOfType(TestImageCover.class)),
                "a row with no target must not match a type predicate, however the discriminator is stored");
        assertTrue(mine(albums.findByCoverOfTypeNot(TestImageCover.class)).isEmpty(),
                "nor must it match the negation -- SQL's null semantics, and the right answer here");
    }

    @Test
    void findsEveryRowWhoseTargetIsNotOfOneType() {
        saveImageBacked("a");
        TestAlbum lottieBacked = saveLottieBacked("b");

        assertEquals(Set.of(lottieBacked.getId()), mine(albums.findByCoverOfTypeNot(TestImageCover.class)));
    }

    @Test
    void findsRowsWhoseTargetIsOfAnyOfSeveralTypes() {
        TestAlbum imageBacked = saveImageBacked("a");
        TestAlbum lottieBacked = saveLottieBacked("b");
        albums.save(new TestAlbum(tag + " coverless", null));

        assertEquals(Set.of(imageBacked.getId(), lottieBacked.getId()),
                mine(albums.findByCoverOfTypeIn(List.of(TestImageCover.class, TestLottieCover.class))));
    }

    @Test
    void countsAndExistsByTargetType() {
        saveImageBacked("a");
        saveImageBacked("b");
        saveLottieBacked("c");

        // count/exists have no title predicate to scope them, so they are asserted relatively: this test's
        // three rows must move the count by exactly two.
        long before = albums.countByCoverOfType(TestImageCover.class);
        saveImageBacked("d");

        assertEquals(before + 1, albums.countByCoverOfType(TestImageCover.class),
                "a count projection must apply the discriminator predicate, not ignore it");
        assertTrue(albums.existsByCoverOfType(TestLottieCover.class));
    }

    @Test
    void composesWithAnOrdinaryPredicateUnderAnd() {
        TestAlbum wanted = saveImageBacked("wanted");
        saveImageBacked("other");
        saveLottieBacked("wanted-but-lottie");

        assertEquals(Set.of(wanted.getId()),
                idsOf(albums.findByCoverOfTypeAndTitle(TestImageCover.class, tag + " wanted")));
    }

    @Test
    void composesWithAnOrdinaryPredicateUnderOr() {
        TestAlbum byTitle = saveLottieBacked("named");
        TestAlbum byType = saveImageBacked("unnamed");

        assertEquals(Set.of(byTitle.getId(), byType.getId()),
                mine(albums.findByTitleOrCoverOfType(tag + " named", TestImageCover.class)),
                "an OfType atom is an ordinary member of the OR-of-ANDs tree, not a special case bolted on");
    }

    @Test
    void composesWithStaticOrdering() {
        TestAlbum second = albums.save(new TestAlbum(tag + " zzz", new TestImageCover("z")));
        TestAlbum first = albums.save(new TestAlbum(tag + " aaa", new TestImageCover("a")));
        saveLottieBacked("excluded");

        List<TestAlbum> ordered = albums.findByCoverOfTypeOrderByTitleAsc(TestImageCover.class).stream()
                .filter(album -> album.getTitle().startsWith(tag))
                .toList();

        assertEquals(List.of(first.getId(), second.getId()), ordered.stream().map(TestAlbum::getId).toList());
    }

    // ---- the same keyword inside a narrowed vector search ------------------------------------------

    @Test
    void narrowsAVectorSearchByTargetType() {
        TestAlbum imageBacked = saveImageBacked("searchable");
        saveLottieBacked("searchable");

        EmbeddingVector reference = albums.findById(imageBacked.getId()).orElseThrow().fieldVector("title");

        List<TestAlbum> hits = albums.findNearestByTitleVectorAndCoverOfType(
                reference, 10, TestImageCover.class);

        assertTrue(hits.stream().map(TestAlbum::getId).toList().contains(imageBacked.getId()));
        assertTrue(hits.stream().noneMatch(album -> album.getCover() instanceof TestLottieCover),
                "the narrowing predicate must be applied by the ranking query, not after it");
    }

    @Test
    void narrowsARankedVectorSearchByAnyOfSeveralTargetTypes() {
        TestAlbum imageBacked = saveImageBacked("ranked");
        EmbeddingVector reference = albums.findById(imageBacked.getId()).orElseThrow().fieldVector("title");

        List<Ranked<TestAlbum>> hits = albums.findNearestByTitleVectorAndCoverOfTypeIn(
                reference, 10, List.of(TestImageCover.class, TestLottieCover.class));

        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(hit -> hit.similarity() >= -1.0 && hit.similarity() <= 1.0));
        assertTrue(hits.stream().allMatch(hit -> hit.entity().getCover() != null));
    }

    @Test
    void theBuilderSpellsTheSameThingAndAgrees() {
        TestAlbum imageBacked = saveImageBacked("builder");
        saveLottieBacked("builder");
        EmbeddingVector reference = albums.findById(imageBacked.getId()).orElseThrow().fieldVector("title");

        List<TestAlbum> viaMethodName =
                albums.findNearestByTitleVectorAndCoverOfType(reference, 10, TestImageCover.class);
        List<TestAlbum> viaBuilder = albums.nearestBy("title").to(reference)
                .where("cover").ofType(TestImageCover.class)
                .limit(10).results();

        assertEquals(viaMethodName.stream().map(TestAlbum::getId).toList(),
                viaBuilder.stream().map(TestAlbum::getId).toList(),
                "the two idioms compile to the same NearestSpec, so they cannot answer differently");
    }

    @Test
    void theBuilderRefusesOfTypeOnAPropertyThatIsNotAnAny() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> albums.nearestBy("title").where("title").ofType(TestImageCover.class));

        assertTrue(thrown.getMessage().contains("not an @Any field"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("cover"), "and must name the ones that are: " + thrown.getMessage());
    }

    // ---- refusals, all at repository-creation time -------------------------------------------------

    @Test
    void refusesTraversingThroughAnAny() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.TraversesThroughAny.class, config));

        assertTrue(thrown.getMessage().contains("@Any"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("different tables"),
                "the message must say why, not just that: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("CoverOfType"),
                "and point at what to write instead: " + thrown.getMessage());
    }

    @Test
    void refusesSortingByAnAny() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.SortsByAny.class, config));

        assertTrue(thrown.getMessage().contains("@Any"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("order by"), thrown.getMessage());
    }

    @Test
    void refusesATextOperatorAgainstAnAnyAssociation() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.MatchesAnyWithLike.class, config));

        assertTrue(thrown.getMessage().contains("@Any"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("OfType"),
                "the refusal must name the operator set that does work: " + thrown.getMessage());
    }

    @Test
    void refusesATextOperatorAgainstATargetTypeComparison() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.OfTypeWithATextOperator.class, config));

        assertTrue(thrown.getMessage().contains("compares a target's type"), thrown.getMessage());
    }

    @Test
    void refusesOfTypeBindingSomethingOtherThanAClass() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.OfTypeBindingTheWrongType.class, config));

        assertTrue(thrown.getMessage().contains("Class<?>"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("java.lang.String"),
                "naming what was declared instead: " + thrown.getMessage());
    }

    @Test
    void refusesOfTypeInBindingASingleClass() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.OfTypeInBindingASingleClass.class, config));

        assertTrue(thrown.getMessage().contains("Collection<Class<?>>"), thrown.getMessage());
    }

    /**
     * The one shape the keyword genuinely cannot resolve, refused rather than guessed.
     *
     * <p>Stripping {@code OfType} makes both atoms read as the same property, and nothing left in the name
     * says which of them carried the keyword. Counting parts is what detects it -- a name-level tokenizer
     * would have had to re-implement {@code PartTree} to notice at all.
     */
    @Test
    void refusesTheSameAnyPropertyNamedTwiceWithTheKeywordOnOnlyOne() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.OfTypeAmbiguouslyRepeated.class, config));

        assertTrue(thrown.getMessage().contains("nothing in the method name says which"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("@Query"),
                "and offers the way out: " + thrown.getMessage());
    }

    @Test
    void refusesOfTypeOnAPropertyThatIsNotAnAny() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.OfTypeOnANonAnyProperty.class, config));

        assertTrue(thrown.getMessage().contains("OfType"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("cover"),
                "the hint must name the @Any fields that do exist: " + thrown.getMessage());
    }

    @Test
    void refusesOfTypeOutsideANarrowingPredicate() {
        assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.OfTypeOutsideANarrowingPredicate.class, config));
    }

    @Test
    void saysSoWhenTheEntityHasNoAnyFieldAtAll() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> JavAIPI.repository(BogusAnyRepositories.OfTypeWhereNoAnyExists.class, config));

        assertTrue(thrown.getMessage().contains("no @Any field at all"), thrown.getMessage());
    }

    /** A property literally named {@code <anyField>OfType} wins over the keyword, so the rewrite cannot eat
     *  a name that means something else. Asserted structurally, since no fixture declares such a field. */
    @Test
    void aRealPropertyNamedLikeTheKeywordWouldWinOverIt() {
        DerivedFinderQuery.AnyTypeRewrite rewrite =
                DerivedFinderQuery.stripAnyTypeKeyword("findByCoverOfType", TestAlbum.class);

        assertEquals("findByCover", rewrite.cleanName());
        assertEquals(1, rewrite.strippedCounts().get("cover"),
                "and it records how many times it stripped, which is what makes the part matching exact");
    }

    /**
     * One {@code @Any} field's name being a suffix of another's -- the shape that broke the first version of
     * the rewrite, and could not be seen from a fixture with a single {@code @Any}.
     *
     * <p>{@code findByAltCoverOfType} ends in {@code CoverOfType} too, so a scan that took the first matching
     * field stripped on behalf of {@code cover}, flagged a part that was not there, and rejected an
     * unambiguous method as ambiguous. Nor can the character before the token separate them: it is lowercase
     * in both {@code findBy|Cover} and {@code Alt|Cover}. Only the longest match is right.
     */
    @Test
    void resolvesTheKeywordToTheLongestAnyFieldNameThatEndsThere() {
        DerivedFinderQuery.AnyTypeRewrite alt =
                DerivedFinderQuery.stripAnyTypeKeyword("findByAltCoverOfType", TestPoster.class);
        assertEquals("findByAltCover", alt.cleanName());
        assertEquals(java.util.Map.of("altCover", 1), alt.strippedCounts(),
                "the longer field owns the keyword; the shorter one must not claim it");

        DerivedFinderQuery.AnyTypeRewrite shorter =
                DerivedFinderQuery.stripAnyTypeKeyword("findByCoverOfType", TestPoster.class);
        assertEquals(java.util.Map.of("cover", 1), shorter.strippedCounts(),
                "and the shorter one still owns its own");

        DerivedFinderQuery.AnyTypeRewrite both = DerivedFinderQuery.stripAnyTypeKeyword(
                "findByCoverOfTypeAndAltCoverOfType", TestPoster.class);
        assertEquals("findByCoverAndAltCover", both.cleanName());
        assertEquals(java.util.Map.of("cover", 1, "altCover", 1), both.strippedCounts());
    }

    @Test
    void queriesEitherOfTwoOverlappingAnyFieldsByTargetType() {
        TestPosterRepository posters = JavAIPI.repository(TestPosterRepository.class, config);
        TestPoster poster = posters.save(new TestPoster(tag + " poster",
                new TestImageCover(tag + " main"), new TestLottieCover(tag + " alt")));

        assertEquals(List.of(poster.getId()),
                posters.findByCoverOfType(TestImageCover.class).stream()
                        .filter(p -> p.getName().startsWith(tag)).map(TestPoster::getId).toList());
        assertEquals(List.of(poster.getId()),
                posters.findByAltCoverOfType(TestLottieCover.class).stream()
                        .filter(p -> p.getName().startsWith(tag)).map(TestPoster::getId).toList(),
                "the alt field must resolve to its own discriminator column, not the other one's");
        assertTrue(posters.findByAltCoverOfType(TestImageCover.class).stream()
                        .noneMatch(p -> p.getName().startsWith(tag)),
                "and must not match on the other field's value");
        assertEquals(List.of(poster.getId()),
                posters.findByCoverOfTypeAndAltCoverOfType(TestImageCover.class, TestLottieCover.class)
                        .stream().filter(p -> p.getName().startsWith(tag)).map(TestPoster::getId).toList());
    }

    @Test
    void leavesANameWithoutTheKeywordCompletelyAlone() {
        DerivedFinderQuery.AnyTypeRewrite rewrite =
                DerivedFinderQuery.stripAnyTypeKeyword("findByCoverAndTitle", TestAlbum.class);

        assertEquals("findByCoverAndTitle", rewrite.cleanName());
        assertTrue(rewrite.isEmpty());
    }
}
