package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.ExternalVector;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which models a container owes a summary row in, decided by reflection alone (OMI-458).
 *
 * <p>No database and no containers: the answer is a property of the declared model, and pinning it here
 * rather than only through a persistence test is what makes a failure say <em>which</em> half is wrong. The
 * walk being transitive and the opt-in gating it are two independent ways to be wrong, and both are silent
 * -- a container simply keeps folding in memory and nothing complains.
 */
class PerModelSummaryDiscoveryTest {

    static final String LEAF_MODEL = "fake-leaf-model/pp1";
    static final String OTHER_LEAF_MODEL = "fake-other-leaf-model/pp1";

    @Entity
    @ExternalVector(name = "pixels", keyField = "contentKey", model = LEAF_MODEL)
    static class Leaf {
        @Id
        private UUID id;
        @Vectorize
        private String caption;
        private String contentKey;
    }

    /** A second leaf type under the same declared collection element type, so the walk has to expand a
     *  declared {@code childType} to its registered subtypes rather than trusting the declaration. */
    @Entity
    @ExternalVector(name = "waveform", keyField = "contentKey", model = OTHER_LEAF_MODEL)
    static class SpecialLeaf extends Leaf {
    }

    @Entity
    @Summary(persistModelSummaries = true)
    static class Middle {
        @Id
        private UUID id;
        @Summary
        private JavAIList<Leaf> leaves = new JavAIArrayList<>();
    }

    @Entity
    @Summary(persistModelSummaries = true)
    static class Top {
        @Id
        private UUID id;
        @Summary
        private JavAIList<Middle> middles = new JavAIArrayList<>();
    }

    @Entity
    static class UnflaggedMiddle {
        @Id
        private UUID id;
        @Summary
        private JavAIList<Leaf> leaves = new JavAIArrayList<>();
    }

    /** A {@code @Summary} cycle is legal -- Vector Core is cycle-safe by design -- so the walk must be too. */
    @Entity
    @Summary(persistModelSummaries = true)
    static class CyclicA {
        @Id
        private UUID id;
        @Summary
        private JavAIList<CyclicB> bs = new JavAIArrayList<>();
    }

    @Entity
    static class CyclicB {
        @Id
        private UUID id;
        @Summary
        private JavAIList<CyclicA> as = new JavAIArrayList<>();
        @Summary
        private JavAIList<Leaf> leaves = new JavAIArrayList<>();
    }

    @Entity
    static class FieldPlacement {
        @Id
        private UUID id;
        @Summary(persistModelSummaries = true)
        private JavAIList<Leaf> leaves = new JavAIArrayList<>();
    }

    private static Containment containmentOf(Class<?>... types) {
        return Containment.of(List.of(types));
    }

    @Test
    void oneHopFindsTheModelItsMembersDeclare() {
        Containment containment = containmentOf(Middle.class, Leaf.class);
        assertEquals(Set.of(LEAF_MODEL), containment.perModelSummaryModels(Middle.class));
    }

    @Test
    void twoHopsFindTheModelDeclaredOnTheGrandchild() {
        // ⚠️ The whole of "summaries of summaries" on the discovery side. Top declares nothing, Middle
        // declares nothing, and Top's summary in LEAF_MODEL is nonetheless a real, computable vector.
        Containment containment = containmentOf(Top.class, Middle.class, Leaf.class);
        assertEquals(Set.of(LEAF_MODEL), containment.perModelSummaryModels(Top.class));
    }

    @Test
    void aSubtypeOfTheDeclaredElementTypeIsFoundToo() {
        // The declaration says JavAIList<Leaf>; the model that matters is on a subclass of Leaf. Following
        // the declared type alone would miss it, and an adopter whose @Summary collection is typed to a
        // @MappedSuperclass is the ordinary case, not a corner one.
        Containment containment = containmentOf(Middle.class, Leaf.class, SpecialLeaf.class);
        assertEquals(Set.of(LEAF_MODEL, OTHER_LEAF_MODEL), containment.perModelSummaryModels(Middle.class));
    }

    @Test
    void withoutTheFlagTheAnswerIsEmptyThoughTheModelIsStillDeclared() {
        Containment containment = containmentOf(UnflaggedMiddle.class, Leaf.class);
        assertEquals(Set.of(), containment.perModelSummaryModels(UnflaggedMiddle.class),
                "nothing extra is written for a container that did not ask");
        assertEquals(Set.of(LEAF_MODEL), containment.declaredSubtreeModels(UnflaggedMiddle.class),
                "but the query side still has to know the model is reachable, or it cannot tell an "
                        + "unindexed question from an unrelated one");
    }

    @Test
    void aSummaryCycleTerminates() {
        Containment containment = containmentOf(CyclicA.class, CyclicB.class, Leaf.class);
        assertEquals(Set.of(LEAF_MODEL), containment.perModelSummaryModels(CyclicA.class));
    }

    @Test
    void hasNoPerModelSummariesIsTrueForAModelThatNeverAsked() {
        assertTrue(containmentOf(UnflaggedMiddle.class, Leaf.class).hasNoPerModelSummaries());
        assertFalse(containmentOf(Middle.class, Leaf.class).hasNoPerModelSummaries());
    }

    @Test
    void theFlagOnAFieldIsRefusedRatherThanIgnored() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Containment.validatePlacement(FieldPlacement.class));
        assertTrue(refused.getMessage().contains("leaves"));
        assertTrue(refused.getMessage().contains("FieldPlacement"),
                "the message has to name the type to move it to, or it is a puzzle rather than a fix");
    }
}
