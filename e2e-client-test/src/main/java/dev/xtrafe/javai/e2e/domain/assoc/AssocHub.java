package dev.xtrafe.javai.e2e.domain.assoc;

import dev.xtrafe.javai.annotations.JavAIVectorizable;
import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Vectorize;
import dev.xtrafe.javai.model.JavAIArrayList;
import dev.xtrafe.javai.model.JavAIList;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The OMI-161 regression matrix: one {@code @JavAIVectorizable} entity carrying <b>every</b> singular and
 * collection association shape at once, each configured purely by annotation, so a single {@code save()}
 * exercises all of them together the way a real consumer entity does.
 *
 * <p><b>What broke, and why this shape is the one that catches it.</b> {@code save()} merges the caller's
 * detached instance, and Hibernate's merged copy holds an <em>uninitialized proxy</em> for a
 * {@code FetchType.LAZY} singular association rather than loading the target. That proxy subclasses the real
 * entity, so it satisfies {@code instanceof JavAIVectorizable} -- but a proxy holds no field state, so
 * reading its {@code @Id} reflectively yielded {@code null} and the vector INSERT died on {@code owner_id}'s
 * NOT NULL constraint. Every fetch mode below is therefore paired: {@code lazy*} against {@code eager*},
 * same target, same cardinality, differing in exactly one annotation attribute.
 *
 * <p><b>Why this went unnoticed until a consumer hit it.</b> Every singular association that previously
 * existed anywhere in this project -- {@code Article}'s three {@code @OneToOne}s, and JavAI's own shipped
 * {@code Tag -> TagSet} -- is <em>eager</em> (both {@code @OneToOne} and an unqualified {@code @ManyToOne}
 * default to {@code EAGER}), so the vector-write walk always happened to find a real, initialized instance.
 * There was no lazy singular association to a vectorizable target anywhere in the suite. That is the gap
 * this class exists to close, permanently.
 *
 * <p><b>These are load-time woven.</b> Living in {@code src/main/java} of {@code e2e-client-test}, they are
 * transformed by the agent {@code JavAIWeavingLauncherSessionListener} installs, exactly like a downstream
 * consumer's own entities -- not build-time woven the way {@code javai-tagging} weaves its shipped
 * {@code Tag}/{@code TagSet}. That distinction was originally suspected of being the trigger; it turned out
 * not to be (the same failure reproduces with unwoven hand-written stand-ins in
 * {@code javai-persistence}'s own {@code SingularAssociationVectorTest}), but proving the load-time-woven
 * path really does work is worth having either way, since it is what every consumer actually runs.
 *
 * <p>Note the absence of {@code implements JavAIVectorizable} and of any {@code vector()}/
 * {@code summaryVector()} method: both are woven in, and calling them requires a cast. Hand-writing either
 * would defeat the mechanism.
 */
@Entity
@JavAIVectorizable
public class AssocHub {

    @Id
    private UUID id = UUID.randomUUID();

    @Vectorize
    private String label;

    // ---- singular associations to a VECTORIZABLE target: the OMI-161 axis --------------------------
    // Each lazy/eager pair differs in exactly one annotation attribute, so a failure localizes to fetch
    // mode rather than to "associations" generally.

    /** The reported failing shape, in its purest form: lazy {@code @ManyToOne} to a vectorizable. */
    @ManyToOne(fetch = FetchType.LAZY)
    private AssocLeaf lazyManyToOne;

    /** The control for {@link #lazyManyToOne} -- identical but eager. Always worked. */
    @ManyToOne(fetch = FetchType.EAGER)
    private AssocLeaf eagerManyToOne;

    /** The consumer's actual repro shape (OMI-161 was filed from a lazy {@code @OneToOne}). */
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "lazy_one_to_one_id")
    private AssocLeaf lazyOneToOne;

    /** The control for {@link #lazyOneToOne}. Matches {@code Article}'s existing eager {@code @OneToOne}s. */
    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "eager_one_to_one_id")
    private AssocLeaf eagerOneToOne;

    /**
     * The ticket explicitly ruled optionality in or out as a factor, so it is pinned here rather than
     * argued about: a mandatory lazy association, {@code optional = false} plus a NOT NULL join column.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mandatory_lazy_id", nullable = false)
    private AssocLeaf mandatoryLazyManyToOne;

    /**
     * Lazy <em>and</em> {@code @Summary}: the association contributes to this hub's
     * {@code summaryVector()}, which means the summary walk has to resolve the same proxy the vector-write
     * walk does. Reading a summary through an uninitialized proxy is a distinct code path from writing one.
     */
    @Summary
    @ManyToOne(fetch = FetchType.LAZY)
    private AssocLeaf summaryLazyManyToOne;

    // ---- singular association to a NON-vectorizable target: the control axis -----------------------

    /** Lazy, but to a target with no vectors at all -- never reproduced the bug, and must stay working. */
    @ManyToOne(fetch = FetchType.LAZY)
    private PlainLeaf lazyPlainTarget;

    // ---- collection associations ------------------------------------------------------------------

    /**
     * Plain (non-JavAI) lazy {@code @OneToMany} of vectorizables -- Hibernate-owned join table.
     *
     * <p>The explicit {@code @JoinTable} name is required, not decoration: JPA's default join-table name is
     * {@code <owner>_<target>}, so all three collections below -- which deliberately share {@link AssocLeaf}
     * as their element type -- would otherwise collapse onto one {@code assoc_hub_assoc_leaf} table with
     * three mutually-exclusive NOT NULL key columns, and the first insert would fail. Found by hitting it.
     */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinTable(name = "assoc_hub_one_to_many")
    private List<AssocLeaf> lazyOneToMany = new ArrayList<>();

    /** Plain lazy {@code @ManyToMany} of vectorizables -- the other join-table cardinality. */
    @ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinTable(name = "assoc_hub_many_to_many")
    private List<AssocLeaf> lazyManyToMany = new ArrayList<>();

    /**
     * The JavAI collection the original ticket suspected. It was a red herring -- the bug reproduced
     * without it and never reproduced from it alone -- so it is kept here deliberately, to hold that
     * conclusion in place rather than leave it as a claim in a comment thread.
     */
    @Summary
    @OneToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "assoc_hub_javai_collection")
    private JavAIList<AssocLeaf> summaryJavAICollection = new JavAIArrayList<>();

    public AssocHub() {
    }

    public AssocHub(String label, AssocLeaf mandatoryLazyManyToOne) {
        this.label = label;
        this.mandatoryLazyManyToOne = mandatoryLazyManyToOne;
    }

    public UUID getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public AssocLeaf getLazyManyToOne() {
        return lazyManyToOne;
    }

    public void setLazyManyToOne(AssocLeaf value) {
        this.lazyManyToOne = value;
    }

    public AssocLeaf getEagerManyToOne() {
        return eagerManyToOne;
    }

    public void setEagerManyToOne(AssocLeaf value) {
        this.eagerManyToOne = value;
    }

    public AssocLeaf getLazyOneToOne() {
        return lazyOneToOne;
    }

    public void setLazyOneToOne(AssocLeaf value) {
        this.lazyOneToOne = value;
    }

    public AssocLeaf getEagerOneToOne() {
        return eagerOneToOne;
    }

    public void setEagerOneToOne(AssocLeaf value) {
        this.eagerOneToOne = value;
    }

    public AssocLeaf getMandatoryLazyManyToOne() {
        return mandatoryLazyManyToOne;
    }

    public void setMandatoryLazyManyToOne(AssocLeaf value) {
        this.mandatoryLazyManyToOne = value;
    }

    public AssocLeaf getSummaryLazyManyToOne() {
        return summaryLazyManyToOne;
    }

    public void setSummaryLazyManyToOne(AssocLeaf value) {
        this.summaryLazyManyToOne = value;
    }

    public PlainLeaf getLazyPlainTarget() {
        return lazyPlainTarget;
    }

    public void setLazyPlainTarget(PlainLeaf value) {
        this.lazyPlainTarget = value;
    }

    public List<AssocLeaf> getLazyOneToMany() {
        return lazyOneToMany;
    }

    public List<AssocLeaf> getLazyManyToMany() {
        return lazyManyToMany;
    }

    public JavAIList<AssocLeaf> getSummaryJavAICollection() {
        return summaryJavAICollection;
    }
}
