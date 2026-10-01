package dev.xtrafe.javai.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Objects;

/**
 * {@link Pageable}s whose offset is <b>not</b> tied to their size (OMI-460).
 *
 * <pre>{@code
 * // page 3 of 20, asking for one extra row to learn whether a page 4 exists
 * List<Album> window = albums.browse(ownerId, Windows.of(60, 21, Sort.by("title")));
 * boolean hasMore = window.size() > 20;
 * }</pre>
 *
 * <h2>Why this exists</h2>
 *
 * JavAI already supports an arbitrary offset -- {@code DeclaredQuery.resolveConstraints} and
 * {@code DerivedFinderQuery.resolveConstraints} both read {@code getOffset()} and {@code getPageSize()},
 * never the page <em>number</em>. What was missing was a way to say one. Spring Data's
 * {@code PageRequest.of(page, size)} derives its offset as {@code page × size}, so every offset it can
 * express is a multiple of the limit, and the standard forever-scroll idiom -- ask for one row more than the
 * page holds, and you know whether there is a next page without a count query -- has no page number that
 * produces it: offset 60 with a limit of 21 would be {@code PageRequest.of(60 / 21, 21)}, which is offset
 * <b>42</b> and quietly the wrong rows.
 *
 * <p>{@code Pageable} is an interface, so an adopter can always implement one. This exists because every
 * adopter doing one-extra-row paging otherwise writes the same class, and discovers the need the same way.
 *
 * <h2>The two page-flavoured methods, and what they mean here</h2>
 *
 * {@code Pageable} is a page-shaped interface, so three of its methods have to answer in pages even though
 * this window has no page number of its own. {@link Pageable#getPageNumber()} reports
 * {@code offset / limit} (floor) -- a description of roughly where the window sits, not a round trip:
 * {@code Windows.of(60, 21).getPageNumber()} is 2, and page 2 of that size starts at 42, not 60. For the
 * same reason {@code withPage(n)} returns an ordinary page-aligned window at {@code n × limit}, since that
 * is the only thing "page n" can mean. {@link Pageable#next()}/{@link Pageable#previousOrFirst()} do stay
 * exact -- they move by the limit from wherever this window actually starts.
 */
public final class Windows {

    private Windows() {
    }

    /** A window of {@code limit} rows starting at {@code offset}, unsorted. */
    public static Pageable of(long offset, int limit) {
        return of(offset, limit, Sort.unsorted());
    }

    /**
     * A window of {@code limit} rows starting at {@code offset}, in {@code sort} order.
     *
     * <p>An unsorted window over an unordered store is not a page of anything -- nothing promises two calls
     * see the same rows in the same order -- so prefer this overload wherever the query does not already
     * carry an {@code ORDER BY} of its own.
     *
     * @throws IllegalArgumentException if {@code offset} is negative or {@code limit} is not positive
     * @throws NullPointerException     if {@code sort} is null
     */
    public static Pageable of(long offset, int limit, Sort sort) {
        return new OffsetWindow(offset, limit, sort);
    }

    /** The {@link Pageable} {@link Windows#of} returns -- offset and size independent of each other. */
    private static final class OffsetWindow implements Pageable {

        private final long offset;
        private final int limit;
        private final Sort sort;

        private OffsetWindow(long offset, int limit, Sort sort) {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative, but was " + offset + ".");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive, but was " + limit
                        + ". Use Pageable.unpaged() to ask for everything.");
            }
            this.offset = offset;
            this.limit = limit;
            this.sort = Objects.requireNonNull(sort, "sort");
        }

        @Override
        public int getPageNumber() {
            return Math.toIntExact(offset / limit);
        }

        @Override
        public int getPageSize() {
            return limit;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return sort;
        }

        @Override
        public Pageable next() {
            return new OffsetWindow(Math.addExact(offset, limit), limit, sort);
        }

        @Override
        public Pageable previousOrFirst() {
            return hasPrevious() ? new OffsetWindow(Math.max(0, offset - limit), limit, sort) : first();
        }

        @Override
        public Pageable first() {
            return new OffsetWindow(0, limit, sort);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            // Page-aligned by definition -- see the class javadoc: "page n" cannot mean anything else, so
            // this is the one place an offset window gives up its own offset rather than quietly keeping it.
            return new OffsetWindow(Math.multiplyExact((long) pageNumber, limit), limit, sort);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OffsetWindow window
                    && offset == window.offset && limit == window.limit && sort.equals(window.sort);
        }

        @Override
        public int hashCode() {
            return Objects.hash(offset, limit, sort);
        }

        @Override
        public String toString() {
            return "Windows.of(offset=" + offset + ", limit=" + limit + ", sort=" + sort + ")";
        }
    }
}
