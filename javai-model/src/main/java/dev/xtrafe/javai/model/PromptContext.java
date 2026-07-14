package dev.xtrafe.javai.model;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * Informing material for a completion -- an ordered bag of {@link Contextable} entries, assembled into one
 * String on demand ({@link #toString()}/{@link #toContext(PromptContext)}). Lives in {@code javai-model},
 * not {@code javai-completion}, alongside {@link Contextable}, for the same module-placement reason
 * documented for {@link JavAIList}/{@link JavAISet}/{@link JavAIMap} in this package's
 * {@code package-info.java}: {@code javai-completion} depends on {@code javai-model}, not the reverse, so
 * anything those collection types implement (here, {@code Contextable}) must live where they do.
 *
 * <p><b>Assembly rules</b> (entries rendered via {@code entry.toContext(this)}, joined with {@code "\n\n"}):
 * <ul>
 *   <li>{@link #sourceLabel()}, if non-null/non-blank, is printed once, as a {@code "[Source: ...]\n"}
 *       header before all entries -- never per-entry, never at all if unset.</li>
 *   <li>{@link #maxLength()} is opt-in; {@code null} (the default) means unbounded. This is what makes
 *       partitioning a context window across several named regions work: each region is its own
 *       {@code PromptContext} with its own budget, honored consistently whether that context is handed
 *       directly to a completion request or nested as one {@code Contextable} entry inside a larger, outer
 *       {@code PromptContext} -- {@link #toContext(PromptContext)} deliberately ignores the passed-in
 *       {@code prompt} argument's own config and always renders using this instance's own entries/label/
 *       budget, then the level above treats the result as one atomic, already-bounded entry to fit or skip
 *       against its own remaining budget.</li>
 *   <li>No partial entries: assembly stops at the first entry whose rendered text would overflow the
 *       remaining budget (preserving order, since entries are typically already relevance-sorted --
 *       e.g. via {@code JavAIList.nearestN}), and nothing is emitted to signal that omission happened.</li>
 *   <li>{@link #targetPercentage()} sizes a <em>nested</em> {@code PromptContext} entry against however
 *       much of this context's own {@link #maxLength()} remains at that point in assembly -- but only when
 *       this context has a {@code maxLength} at all, and only for a nested entry that doesn't already carry
 *       its own explicit {@code maxLength} (that always wins, same "manual override" rule as everywhere
 *       else here). The percentage is normalized against the sum of every sibling nested entry's own
 *       percentage that's likewise eligible (unbounded, no explicit {@code maxLength}) -- not against 1.0
 *       directly -- so a handful of regions sharing "the rest of the budget" split it proportionally
 *       between themselves. A qualifying nested entry with no {@code targetPercentage} set throws
 *       {@link IllegalStateException} at assembly time rather than silently claiming a 0% share.</li>
 * </ul>
 *
 * <p><b>{@link #defaultMarshall(Object)} uses GSON, not Jackson</b> -- deliberately: neither {@code javai-vector}
 * nor {@code javai-model} takes a Jackson dependency of its own (only {@code javai-completion} sees Jackson
 * at all, transitively via Spring AI). GSON's default reflective serialization has no cycle guard equivalent to this project's
 * own {@code JavAIRuntime.enterSummaryComputation}/{@code exitSummaryComputation} (used by
 * {@code summaryVector()}'s recursive walk) -- a self-referential or graph-shaped object risks a stack
 * overflow. Pass a custom {@link Gson} (via {@link Builder#gson(Gson)}) with the relevant type adapters for
 * anything GSON can't handle by default, or override {@link Contextable#toContext(PromptContext)} on the
 * object itself.
 *
 * <p><b>The default {@code Gson} only serializes fields annotated
 * {@code @}{@link dev.xtrafe.javai.annotations.PromptContext}</b> -- an allowlist, not a blocklist. A woven
 * {@code @JavAIVectorizable} class carries internal bookkeeping fields (a cached
 * {@link dev.xtrafe.javai.vector.EmbeddingVector},
 * dirty-tracking state) that must never leak into a prompt just because an object happened to get wrapped
 * in {@link ContextableObject}; requiring an explicit opt-in per field is the only way to guarantee that
 * without hand-maintaining a blocklist of "things that look internal." A caller that wants GSON's ordinary,
 * unfiltered reflection can still get it via {@link Builder#gson(Gson)} with a plain {@code new Gson()} (or
 * one with its own {@code ExclusionStrategy}) -- this default is an opinionated starting point, not the
 * only option. Note the annotation shares its simple name with this class (see its own javadoc for why);
 * it's referenced fully-qualified below rather than imported, since {@code PromptContext} is already this
 * file's own type name.
 *
 * <p><b>Also fulfills the full {@code List<Contextable>} contract</b>, delegating every method to
 * {@link #entries()} -- so a whole {@code List<Contextable>} (including another {@code PromptContext},
 * since it's now itself one) can be added directly via {@link #addAll(Collection)} to an already-built
 * instance, not just at construction time via {@link Builder#entries(Collection)}. Still a record: the
 * delegate ({@code entries}, a mutable {@link JavAIList}) is itself where the mutation lands, not a record
 * component being reassigned. {@link #equals(Object)}/{@link #hashCode()} are overridden to delegate to
 * {@code entries} too, deliberately replacing the record's default component-wise equality with
 * {@code List}'s own documented general contract (equal iff same elements in the same order, regardless of
 * concrete type) -- otherwise two contexts with identical entries but a different {@code sourceLabel} would
 * never compare equal as lists, which would violate the contract this class now advertises.
 */
public record PromptContext(
        JavAIList<Contextable> entries, String sourceLabel, Integer maxLength, Gson gson, Double targetPercentage)
        implements List<Contextable>, Contextable {

    private static final Gson DEFAULT_GSON = new GsonBuilder()
            .setExclusionStrategies(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    return f.getAnnotation(dev.xtrafe.javai.annotations.PromptContext.class) == null;
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return false;
                }
            })
            .create();
    private static final String ENTRY_SEPARATOR = "\n\n";

    public PromptContext {
        entries = entries == null ? new JavAIArrayList<>() : entries;
        gson = gson == null ? DEFAULT_GSON : gson;
        if (maxLength != null && maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be >= 0, got " + maxLength);
        }
        if (targetPercentage != null && (targetPercentage <= 0.0 || targetPercentage > 1.0)) {
            throw new IllegalArgumentException("targetPercentage must be in (0.0, 1.0], got " + targetPercentage);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Wraps plain text as a single entry -- no GSON marshalling, since it's already display text. */
    public static PromptContext of(String text) {
        return builder().entry(new PlainTextEntry(requireText(text))).build();
    }

    public static PromptContext of(String text, String sourceLabel) {
        return builder().entry(new PlainTextEntry(requireText(text))).sourceLabel(sourceLabel).build();
    }

    private static String requireText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        return text;
    }

    /** The shared marshalling helper every {@link Contextable}'s default implementation calls into. */
    public String defaultMarshall(Object value) {
        return gson.toJson(value);
    }

    /** Concatenates this context's entries with {@code other}'s, keeping this context's sourceLabel/
     *  maxLength/gson/targetPercentage. */
    public PromptContext merge(PromptContext other) {
        JavAIList<Contextable> combined = new JavAIArrayList<>(entries);
        combined.addAll(other.entries());
        return new PromptContext(combined, sourceLabel, maxLength, gson, targetPercentage);
    }

    /** A copy of this context with a different {@link #maxLength()}, re-triggering assembly under the new
     *  budget rather than slicing an already-assembled string. */
    public PromptContext withMaxLength(int maxLength) {
        return new PromptContext(entries, sourceLabel, maxLength, gson, targetPercentage);
    }

    /**
     * Ignores {@code prompt} -- this context already carries a complete config of its own (entries,
     * sourceLabel, maxLength, gson), so nesting doesn't defer to the outer context's config. See this
     * class's own javadoc for why that's useful, not an oversight.
     */
    @Override
    public String toContext(PromptContext prompt) {
        return assemble();
    }

    @Override
    public String toString() {
        return assemble();
    }

    private String assemble() {
        StringBuilder buffer = new StringBuilder();
        if (sourceLabel != null && !sourceLabel.isBlank()) {
            buffer.append("[Source: ").append(sourceLabel).append("]\n");
        }
        // Only meaningful when this context itself has a budget -- an unbounded PromptContext has nothing
        // to divide among nested entries, so they render unbounded too, same as before this feature existed.
        Double percentageSum = maxLength == null ? null : sumOfEligibleTargetPercentages();
        boolean first = true;
        for (Contextable entry : entries) {
            Contextable effectiveEntry = entry;
            if (percentageSum != null && entry instanceof PromptContext nested && nested.maxLength() == null) {
                int remaining = maxLength - buffer.length();
                double share = nested.targetPercentage() / percentageSum;
                int allocated = Math.max(0, (int) (remaining * share));
                effectiveEntry = nested.withMaxLength(allocated);
            }
            String rendered = effectiveEntry.toContext(this);
            String candidate = first ? rendered : ENTRY_SEPARATOR + rendered;
            if (maxLength != null && buffer.length() + candidate.length() > maxLength) {
                break;
            }
            buffer.append(candidate);
            first = false;
        }
        return buffer.toString();
    }

    /**
     * Sums {@link #targetPercentage()} across nested {@code PromptContext} entries that lack their own
     * explicit {@link #maxLength()} (an explicit {@code maxLength} opts an entry out of the proportional
     * split entirely -- "manual override always wins", same rule as everywhere else in this class). Throws
     * if any such entry is missing {@code targetPercentage} -- there'd be no way to compute its share of
     * this context's budget otherwise, and silently defaulting to a 0% share would be a confusing, silent
     * failure to debug later.
     */
    private double sumOfEligibleTargetPercentages() {
        double sum = 0;
        for (Contextable entry : entries) {
            if (entry instanceof PromptContext nested && nested.maxLength() == null) {
                if (nested.targetPercentage() == null) {
                    throw new IllegalStateException("A nested PromptContext with no explicit maxLength must set "
                            + "targetPercentage, so its share of the parent's budget can be computed");
                }
                sum += nested.targetPercentage();
            }
        }
        return sum;
    }

    // -- List<Contextable> contract: every method below delegates to `entries` (see class javadoc). --

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return entries.contains(o);
    }

    @Override
    public Iterator<Contextable> iterator() {
        return entries.iterator();
    }

    @Override
    public Object[] toArray() {
        return entries.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return entries.toArray(a);
    }

    @Override
    public boolean add(Contextable contextable) {
        return entries.add(contextable);
    }

    @Override
    public boolean remove(Object o) {
        return entries.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return entries.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends Contextable> c) {
        return entries.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends Contextable> c) {
        return entries.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return entries.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return entries.retainAll(c);
    }

    @Override
    public void clear() {
        entries.clear();
    }

    @Override
    public Contextable get(int index) {
        return entries.get(index);
    }

    @Override
    public Contextable set(int index, Contextable element) {
        return entries.set(index, element);
    }

    @Override
    public void add(int index, Contextable element) {
        entries.add(index, element);
    }

    @Override
    public Contextable remove(int index) {
        return entries.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return entries.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return entries.lastIndexOf(o);
    }

    @Override
    public ListIterator<Contextable> listIterator() {
        return entries.listIterator();
    }

    @Override
    public ListIterator<Contextable> listIterator(int index) {
        return entries.listIterator(index);
    }

    @Override
    public List<Contextable> subList(int fromIndex, int toIndex) {
        return entries.subList(fromIndex, toIndex);
    }

    /** Overrides the record's default component-wise equality with {@code List}'s own general contract --
     *  see class javadoc for why. */
    @Override
    public boolean equals(Object o) {
        return entries.equals(o);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    public static final class Builder {
        private final JavAIList<Contextable> entries = new JavAIArrayList<>();
        private String sourceLabel;
        private Integer maxLength;
        private Gson gson;
        private Double targetPercentage;

        private Builder() {
        }

        public Builder entry(Contextable entry) {
            entries.add(entry);
            return this;
        }

        public Builder entries(Collection<? extends Contextable> newEntries) {
            entries.addAll(newEntries);
            return this;
        }

        public Builder sourceLabel(String sourceLabel) {
            this.sourceLabel = sourceLabel;
            return this;
        }

        public Builder maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        /** Override the default {@link Gson} instance, e.g. to register a type adapter for a class
         *  {@link #defaultMarshall(Object)} can't handle out of the box. */
        public Builder gson(Gson gson) {
            this.gson = gson;
            return this;
        }

        /**
         * This context's desired share, in {@code (0.0, 1.0]}, of whatever budget remains when it's nested
         * inside an outer {@code PromptContext} that has its own {@link #maxLength()} set. Only consulted
         * when this context is nested <em>and</em> doesn't already have its own explicit {@code maxLength}
         * (an explicit one always wins); ignored entirely for a top-level context. See the class javadoc's
         * assembly rules for exactly how the share is computed.
         */
        public Builder targetPercentage(double targetPercentage) {
            this.targetPercentage = targetPercentage;
            return this;
        }

        public PromptContext build() {
            return new PromptContext(entries, sourceLabel, maxLength, gson, targetPercentage);
        }
    }
}
