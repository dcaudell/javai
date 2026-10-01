package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.vector.EmbeddingVector;
import dev.xtrafe.javai.vector.Ranked;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code findNearestBy…Vector…} name grammar itself, parsed directly (OMI-230).
 *
 * <p>Separate from {@link NarrowedVectorSearchTest} because this asks a different question and can answer it
 * without a database: given a method <em>name and signature</em>, does the parser decide the right thing,
 * and does it refuse the wrong thing with a message that says why? Every case here is settled at
 * repository-creation time in real use, which is precisely why it deserves tests that do not need a store to
 * be running.
 */
class VectorQueryNameGrammarTest {

    /** Located by name <em>and</em> parameter types, since several cases below are overloads that differ
     *  only in how they are bounded or what they return. */
    private static DerivedQueryMethods.ParsedQuery parse(Class<?> repository, String method, Class<?>... params) {
        Method candidate;
        try {
            candidate = repository.getDeclaredMethod(method, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("no such method on " + repository, e);
        }
        return DerivedQueryMethods.parse(candidate, TestAmbiguousNames.class);
    }

    private static IllegalArgumentException parseFailure(Class<?> repository, String method, Class<?>... params) {
        return assertThrows(IllegalArgumentException.class, () -> parse(repository, method, params));
    }

    // ---- which "Vector" is the keyword ------------------------------------------------------------

    @Test
    @DisplayName("a @Vectorize field whose own name ends in Vector still resolves")
    void fieldNameEndingInVectorResolves() {
        DerivedQueryMethods.ParsedQuery parsed =
                parse(Valid.class, "findNearestBySubVectorVector", EmbeddingVector.class, int.class);

        assertEquals(DerivedQueryMethods.Kind.FIELD, parsed.kind());
        assertEquals("subVector", parsed.fieldName());
        assertFalse(parsed.isNarrowed());
    }

    @Test
    @DisplayName("...and still resolves when a predicate follows it")
    void fieldNameEndingInVectorResolvesWithAPredicate() {
        DerivedQueryMethods.ParsedQuery parsed = parse(Valid.class, "findNearestBySubVectorVectorAndVectorNameIs",
                EmbeddingVector.class, int.class, String.class);

        assertEquals("subVector", parsed.fieldName());
        assertTrue(parsed.isNarrowed());
    }

    /** The mirror case: the rightmost {@code Vector} belongs to the <em>predicate</em>, so the scan must
     *  keep going left rather than splitting at the first one it finds. */
    @Test
    @DisplayName("a predicate property containing Vector does not steal the split")
    void predicatePropertyContainingVectorDoesNotStealTheSplit() {
        DerivedQueryMethods.ParsedQuery parsed = parse(Valid.class, "findNearestByCaptionVectorAndVectorNameContaining",
                EmbeddingVector.class, int.class, String.class);

        assertEquals(DerivedQueryMethods.Kind.FIELD, parsed.kind());
        assertEquals("caption", parsed.fieldName());
        assertTrue(parsed.isNarrowed());
    }

    // ---- the whole-object kinds -------------------------------------------------------------------

    @Test
    @DisplayName("the combined and summary kinds parse, narrowed or not")
    void wholeObjectKindsParse() {
        assertEquals(DerivedQueryMethods.Kind.COMBINED,
                parse(Valid.class, "findNearestByVector", EmbeddingVector.class, int.class).kind());
        assertEquals(DerivedQueryMethods.Kind.SUMMARY,
                parse(Valid.class, "findNearestBySummaryVector", EmbeddingVector.class, int.class).kind());

        DerivedQueryMethods.ParsedQuery narrowed = parse(Valid.class, "findNearestBySummaryVectorAndCaptionIs",
                EmbeddingVector.class, int.class, String.class);
        assertEquals(DerivedQueryMethods.Kind.SUMMARY, narrowed.kind());
        assertTrue(narrowed.isNarrowed());
    }

    // ---- return shape and limit source ------------------------------------------------------------

    @Test
    @DisplayName("List<Ranked<T>> is recognized as a ranked return, List<T> is not")
    void rankedReturnIsRecognized() {
        assertTrue(parse(Valid.class, "findNearestBySubVectorVector",
                EmbeddingVector.class, Limit.class).ranked());
        assertFalse(parse(Valid.class, "findNearestByVector", EmbeddingVector.class, int.class).ranked());
    }

    @Test
    @DisplayName("the bound may come from an int, a Pageable, or a Limit")
    void theBoundMayComeFromAnyOfThree() {
        assertEquals(1, parse(Valid.class, "findNearestByVector",
                EmbeddingVector.class, int.class).limitParamIndex());
        assertNotNull(parse(Valid.class, "findNearestByCaptionVector",
                EmbeddingVector.class, Pageable.class));
        assertNotNull(parse(Valid.class, "findNearestBySubVectorVector",
                EmbeddingVector.class, Limit.class));
    }

    // ---- refusals ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a name matching no @Vectorize field is refused, listing the ones that exist")
    void unknownFieldIsRefused() {
        IllegalArgumentException thrown = parseFailure(Invalid.class, "findNearestByHeadlineVector",
                EmbeddingVector.class, int.class);

        assertTrue(thrown.getMessage().contains("caption") && thrown.getMessage().contains("subVector"),
                thrown.getMessage());
    }

    @Test
    @DisplayName("a predicate naming an unknown property is refused, naming the entity")
    void unknownPredicatePropertyIsRefused() {
        IllegalArgumentException thrown = parseFailure(Invalid.class, "findNearestByCaptionVectorAndNonesuchIs",
                EmbeddingVector.class, int.class, String.class);

        assertTrue(thrown.getMessage().contains(TestAmbiguousNames.class.getName()), thrown.getMessage());
    }

    @Test
    @DisplayName("a trailing And with no property is refused with an example")
    void danglingAndIsRefused() {
        IllegalArgumentException thrown = parseFailure(Invalid.class, "findNearestByCaptionVectorAnd",
                EmbeddingVector.class, int.class);

        assertTrue(thrown.getMessage().contains("names no property"), thrown.getMessage());
    }

    @Test
    @DisplayName("an argument count that does not match the predicate is refused, stating both counts")
    void argumentCountMismatchIsRefused() {
        IllegalArgumentException thrown = parseFailure(Invalid.class, "findNearestByCaptionVectorAndVectorNameIs",
                EmbeddingVector.class, int.class);

        assertTrue(thrown.getMessage().contains("bind into its narrowing predicate"), thrown.getMessage());
    }

    @Test
    @DisplayName("a query with no bound at all is refused rather than defaulted to unbounded")
    void anUnboundedQueryIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(Invalid.class, "findNearestByCaptionVector", EmbeddingVector.class));
    }

    @Test
    @DisplayName("a reference vector anywhere but first is refused")
    void misplacedReferenceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse(Invalid.class,
                "findNearestByCaptionVectorReferenceLast", int.class, EmbeddingVector.class));
    }

    @Test
    @DisplayName("a Pageable before the bindable arguments is refused")
    void misplacedPageableIsRefused() {
        IllegalArgumentException thrown = parseFailure(Invalid.class, "findNearestByCaptionVectorAndVectorNameIs",
                EmbeddingVector.class, Pageable.class, String.class);

        assertTrue(thrown.getMessage().contains("after all other parameters"), thrown.getMessage());
    }

    // ---- the fixtures the cases above parse -------------------------------------------------------

    @SuppressWarnings("unused") // parsed reflectively, never called
    private interface Valid {
        List<TestAmbiguousNames> findNearestByVector(EmbeddingVector reference, int limit);

        List<TestAmbiguousNames> findNearestBySummaryVector(EmbeddingVector reference, int limit);

        List<TestAmbiguousNames> findNearestBySummaryVectorAndCaptionIs(
                EmbeddingVector reference, int limit, String caption);

        List<TestAmbiguousNames> findNearestBySubVectorVector(EmbeddingVector reference, int limit);

        List<TestAmbiguousNames> findNearestBySubVectorVectorAndVectorNameIs(
                EmbeddingVector reference, int limit, String vectorName);

        List<TestAmbiguousNames> findNearestByCaptionVectorAndVectorNameContaining(
                EmbeddingVector reference, int limit, String fragment);

        /** Ranked, and bounded by a Limit -- an overload of the plain one above, since a return type alone
         *  cannot distinguish two methods. */
        List<Ranked<TestAmbiguousNames>> findNearestBySubVectorVector(EmbeddingVector reference, Limit limit);

        List<TestAmbiguousNames> findNearestByCaptionVector(EmbeddingVector reference, Pageable pageable);
    }

    @SuppressWarnings("unused") // parsed reflectively, never called
    private interface Invalid {
        /** No such @Vectorize field. */
        List<TestAmbiguousNames> findNearestByHeadlineVector(EmbeddingVector reference, int limit);

        /** No such property to narrow by. */
        List<TestAmbiguousNames> findNearestByCaptionVectorAndNonesuchIs(
                EmbeddingVector reference, int limit, String value);

        /** Lead-in with nothing after it. */
        List<TestAmbiguousNames> findNearestByCaptionVectorAnd(EmbeddingVector reference, int limit);

        /** Predicate needs one argument; none declared. */
        List<TestAmbiguousNames> findNearestByCaptionVectorAndVectorNameIs(EmbeddingVector reference, int limit);

        /** No int limit, no Pageable, no Limit. */
        List<TestAmbiguousNames> findNearestByCaptionVector(EmbeddingVector reference);

        /** Reference vector must come first. */
        List<TestAmbiguousNames> findNearestByCaptionVectorReferenceLast(int limit, EmbeddingVector reference);

        /** Pageable must follow the bindable arguments, not precede them. */
        List<TestAmbiguousNames> findNearestByCaptionVectorAndVectorNameIs(
                EmbeddingVector reference, Pageable pageable, String vectorName);
    }
}
