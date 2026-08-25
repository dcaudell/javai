package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Summary;
import dev.xtrafe.javai.annotations.Taggregate;
import dev.xtrafe.javai.model.JavAIRuntime;
import jakarta.persistence.Entity;
import org.hibernate.Session;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which containers a given entity contributes its summary to -- {@code @Summary} read backwards, resolved
 * against the <b>database</b> rather than against whatever object graph happened to be in memory (OMI-255).
 *
 * <h2>Why this cannot be an in-memory walk</h2>
 *
 * {@code JavAIRuntime} already propagates summary staleness upward through back-edges, and for a single
 * process holding the whole graph that is exactly right. It is not enough for a persisted, multi-pod
 * deployment, and the gap is not an edge case -- it is the normal shape of a request:
 *
 * <pre>
 * Library --@Summary--> Shelf --@Summary--> Book
 * </pre>
 *
 * <p>A pod that loads a {@code Shelf} through its own repository and adds a {@code Book} holds no
 * {@code Library} instance at all. There is no back-edge to walk, because the object that would carry it was
 * never loaded. Its summary then keeps whatever value some other pod left behind, and nothing fails: the
 * library simply goes on summarising a collection it no longer accurately describes.
 *
 * <p>So containment is answered the only way that is true regardless of what a session loaded: from the
 * declared model (which types declare {@code @Summary} fields, and of what) plus the stored relationships
 * (which rows are actually in them). The first half is reflection over registered types and is computed
 * once; the second half is a query, and is what makes the answer independent of the caller.
 *
 * <h2>Both storage shapes are covered</h2>
 *
 * A {@code @Summary} collection is a natively mapped association -- a plain JDK collection, or an
 * interface-typed JavAI collection with {@code @OneToMany}/{@code @ManyToMany} -- living in Hibernate's own
 * join table and reached with HQL. A singular {@code @Summary} reference is HQL too. There used to be a
 * second shape, a concrete-typed JavAI collection with its own membership table, reached with SQL; it was
 * withdrawn in OMI-277 and its half of this lookup went with it.
 */
final class Containment {

    /**
     * One declared containment edge: {@code parentType.fieldName} can hold {@code childType}.
     *
     * <p>{@code summary} records whether the field carries {@code @Summary} and {@code taggregate} whether it
     * carries {@code @Taggregate}, because the consumers want different subsets. Summary recomputation cares
     * only about {@code @Summary} fields; Taggregate only about {@code @Taggregate} ones (OMI-304); detaching
     * an entity before deleting it cares about <em>every</em> mapped collection, since a foreign key does not
     * check whether the association was interesting enough to be summarised. The two annotations are
     * independent — a field may carry both, either, or neither.
     */
    record Edge(Class<?> parentType, String fieldName, Class<?> childType, Kind kind, boolean summary,
            boolean taggregate) {
    }

    enum Kind {
        /** Hibernate owns the association -- reachable by HQL through the mapped field. */
        NATIVE_COLLECTION,
        /** A singular reference ({@code @OneToOne}/{@code @ManyToOne}). */
        NATIVE_SINGULAR
    }

    private final List<Edge> edges;

    /** Every registered entity type, kept because {@link #perModelSummaryModels} has to expand a declared
     *  {@code childType} to the subtypes actually stored under it -- a {@code @Summary} collection declared
     *  {@code Collection<Asset>} whose {@code Image} subtype is the one carrying the {@code @ExternalVector}
     *  is the ordinary shape, not a corner case. */
    private final List<Class<?>> registeredTypes;

    /** Memoised per container type: the walk is pure reflection over a fixed type set, so it can only ever
     *  produce one answer, and it is asked once per entity-grain write. */
    private final Map<Class<?>, Set<String>> perModelSummaryModels = new ConcurrentHashMap<>();

    private Containment(List<Edge> edges, List<Class<?>> registeredTypes) {
        this.edges = edges;
        this.registeredTypes = registeredTypes;
    }

    /**
     * Reads every entity-valued field of every registered type once, at the moment the entity set is known
     * and complete. Nothing here touches the database or loads an entity -- it is the declared shape of the
     * model, which cannot change at runtime.
     */
    static Containment of(Collection<Class<?>> registeredEntityTypes) {
        List<Edge> edges = new ArrayList<>();
        for (Class<?> parentType : registeredEntityTypes) {
            for (Field field : EntityReflection.allFields(parentType)) {
                boolean summary = field.isAnnotationPresent(Summary.class);
                boolean taggregate = field.isAnnotationPresent(Taggregate.class);
                Class<?> declared = field.getType();
                if (Map.class.isAssignableFrom(declared) || Collection.class.isAssignableFrom(declared)) {
                    Class<?> element = elementType(field, Map.class.isAssignableFrom(declared) ? 1 : 0);
                    if (element != null && element.isAnnotationPresent(Entity.class)) {
                        // Always native: a collection of entities that is not a mapped association is
                        // refused at registration (OMI-277), so there is no second shape left to detect.
                        edges.add(new Edge(parentType, field.getName(), element, Kind.NATIVE_COLLECTION,
                                summary, taggregate));
                    }
                } else if ((summary || taggregate) && declared.isAnnotationPresent(Entity.class)) {
                    // Singular references are collected only when annotated. A to-one is not something an
                    // entity can be *detached* from on the way to being deleted -- nulling someone else's
                    // field is a change to their data, not a cleanup -- so the delete path has no use for
                    // the rest, and letting the foreign key refuse is the honest outcome.
                    //
                    // @Taggregate joins @Summary here rather than riding on it: a singular @Taggregate
                    // reference is a real adopter shape (an asset absorbing its own social details), and
                    // collecting only @Summary ones would make that field's taggings invisible to every
                    // container above it.
                    edges.add(new Edge(parentType, field.getName(), declared, Kind.NATIVE_SINGULAR,
                            summary, taggregate));
                }
            }
        }
        return new Containment(List.copyOf(edges), List.copyOf(registeredEntityTypes));
    }

    // ---- per-model summary persistence (OMI-458) ------------------------------------------------------

    /**
     * The embedding models {@code containerType} owes a persisted summary row in, beyond the ambient one --
     * empty unless it carries {@code @Summary(persistModelSummaries = true)} at type level.
     *
     * <p>Derived from <b>declarations only</b>: every {@code @ExternalVector(model = ...)} reachable from
     * this type through {@code @Summary} fields, transitively, plus any the container declares itself.
     * Never from what happens to be in a vector table -- an un-embedded corpus and a corpus with no such
     * model are indistinguishable there, and the second would silently and permanently stop writing rows
     * the first is merely waiting for.
     *
     * <p><b>Transitive, because containment is.</b> {@code Exhibition -> Album -> Image} is one
     * {@code @Summary} chain and the exhibition's pixel summary is a real, computable vector
     * ({@code summaryVector(modelId)} recurses and skips the absent terms in between), so stopping the walk
     * at the first hop would store a row for the album and leave the exhibition with the very in-memory fold
     * this exists to remove.
     *
     * <p>The ambient model is <b>not</b> excluded here. It cannot be: the configured provider is a runtime
     * fact and this answer is a compile-time one. The writer excludes it, because that row is written by the
     * ordinary path and always has been.
     */
    Set<String> perModelSummaryModels(Class<?> containerType) {
        return persistsModelSummaries(containerType) ? declaredSubtreeModels(containerType) : Set.of();
    }

    /**
     * Every embedding model declared by an {@code @ExternalVector} reachable from {@code containerType}
     * through {@code @Summary} fields, transitively -- <b>regardless of whether the type opts in</b>.
     *
     * <p>Separate from {@link #perModelSummaryModels} because the query side needs the wider answer. A
     * {@code nearestBySummary()} against one of these models on a type that has <em>not</em> opted in is a
     * question with a real answer and no index; against a model that is not in this set at all it is the
     * ordinary indexed search, untouched. Telling those two apart is what lets the fallback exist without
     * putting a scan anywhere near a query that works today.
     */
    Set<String> declaredSubtreeModels(Class<?> containerType) {
        return perModelSummaryModels.computeIfAbsent(containerType, type -> {
            Set<String> models = new LinkedHashSet<>();
            Set<Class<?>> visited = new LinkedHashSet<>();
            Deque<Class<?>> pending = new ArrayDeque<>();
            pending.add(type);
            while (!pending.isEmpty()) {
                Class<?> current = pending.poll();
                if (!visited.add(current)) {
                    continue; // a @Summary cycle is legal (Vector Core is cycle-safe); revisiting is not
                }
                for (String vectorName : JavAIRuntime.externalVectorNames(current)) {
                    models.add(JavAIRuntime.externalVectorModel(current, vectorName));
                }
                for (Edge edge : edges) {
                    if (!edge.summary() || !edge.parentType().isAssignableFrom(current)) {
                        continue;
                    }
                    pending.add(edge.childType());
                    for (Class<?> registered : registeredTypes) {
                        if (edge.childType().isAssignableFrom(registered)) {
                            pending.add(registered);
                        }
                    }
                }
            }
            return Set.copyOf(models);
        });
    }

    /** Whether any registered type opts in at all -- lets the supply path and the entity-grain writer skip
     *  every part of this feature, including the containment lookup itself, for a model that never asked
     *  for it. */
    boolean hasNoPerModelSummaries() {
        for (Class<?> type : registeredTypes) {
            if (persistsModelSummaries(type)) {
                return false;
            }
        }
        return true;
    }

    /** {@code @Summary(persistModelSummaries = true)} on the type itself, inherited included -- an adopter's
     *  container hierarchy is as likely to declare it on a {@code @MappedSuperclass} as on the leaf. */
    private static boolean persistsModelSummaries(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            Summary declared = current.getDeclaredAnnotation(Summary.class);
            if (declared != null && declared.persistModelSummaries()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refuses {@code @Summary(persistModelSummaries = true)} on a <b>field</b>, where it has no meaning.
     *
     * <p>Refused rather than ignored on the codebase's standing rule about quiet wrongness: a persisted
     * summary row belongs to the container, so a field cannot opt part of one in, and an adopter who wrote
     * it there would get exactly the in-memory fold they were trying to escape with nothing to indicate
     * why. {@link #concatenate} genuinely does mean something different per placement; this does not.
     */
    static void validatePlacement(Class<?> entityType) {
        for (Field field : EntityReflection.allFields(entityType)) {
            Summary declared = field.getAnnotation(Summary.class);
            if (declared != null && declared.persistModelSummaries()) {
                throw new IllegalArgumentException("@Summary(persistModelSummaries = true) on field '"
                        + field.getName() + "' of " + entityType.getName() + " has no meaning -- a persisted"
                        + " per-model summary row belongs to the container as a whole, keyed (owner_type,"
                        + " owner_id), so there is no half of one for a field to opt in. Move it to the type:"
                        + " @Summary(persistModelSummaries = true) on " + entityType.getSimpleName()
                        + " itself. (@Summary(concatenate = true) is the one that is per-placement.)");
            }
        }
    }

    /** Whether anything at all declares a {@code @Summary} field -- lets a model with no summary containment
     *  skip the recomputation machinery entirely, and never provision the queue table. */
    boolean hasNoSummaries() {
        return edges.stream().noneMatch(Edge::summary);
    }

    /** Whether {@code type} can be held by some container's {@code @Summary} field, or is itself one. Used
     *  to keep a plain {@code @Entity} -- or a vectorized one nothing contains -- entirely out of the
     *  queue. */
    boolean participates(Class<?> type) {
        for (Edge edge : edges) {
            if (!edge.summary()) {
                continue;
            }
            if (edge.childType().isAssignableFrom(type) || edge.parentType().isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    /** Every to-many edge that could be holding {@code childType}, {@code @Summary} or not -- what
     *  {@code deleteById} must clear before the row can go. */
    List<Edge> collectionEdgesHolding(Class<?> childType) {
        return edges.stream()
                .filter(edge -> edge.kind() != Kind.NATIVE_SINGULAR)
                .filter(edge -> edge.childType().isAssignableFrom(childType))
                .toList();
    }

    /**
     * Every container currently holding {@code (childType, childId)} through a {@code @Summary} field, as
     * {@code (ownerType, ownerId)} pairs -- read from the database, so it reflects what is committed rather
     * than what any session has in memory.
     */
    Set<OwnerRef> containersOf(Session session, Class<?> childType, UUID childId) {
        Set<OwnerRef> owners = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (!edge.summary() || !edge.childType().isAssignableFrom(childType)) {
                continue;
            }
            // Named from the @Id field rather than assumed to be "id": identity is located by annotation
            // everywhere else in this module, and an entity whose key field is called something else would
            // otherwise fail here with a query-parsing error rather than anywhere near its cause.
            collectHql(session, owners, edge, parentsHql(edge, childType), childId);
        }
        return owners;
    }

    /** The owners currently holding {@code (childType, childId)} through one specific edge. Same lookup
     *  {@link #containersOf} performs, exposed per-edge because the delete path needs to know <em>which</em>
     *  field to remove the child from, not merely that some field does. */
    Set<OwnerRef> ownersHolding(Session session, Edge edge, Class<?> childType, UUID childId) {
        Set<OwnerRef> owners = new LinkedHashSet<>();
        collectHql(session, owners, edge, parentsHql(edge, childType), childId);
        return owners;
    }

    // ---- Taggregate (OMI-304) -- the same declared shape, read for the other annotation ----------------

    /** Whether anything at all declares a {@code @Taggregate} field -- lets a model that uses none skip the
     *  Taggregate machinery entirely, exactly as {@link #hasNoSummaries} does for summaries. */
    boolean hasNoTaggregates() {
        return edges.stream().noneMatch(Edge::taggregate);
    }

    /**
     * Every container currently holding {@code (childType, childId)} through a {@code @Taggregate} field.
     *
     * <p>The Taggregate counterpart of {@link #containersOf}, and the whole reason OMI-304 could delete the
     * {@code javai_taggregate_members} snapshot: the join table <em>is</em> the membership, so this answer
     * cannot be stale and needs no prior reconciliation pass to become true. A container that has never been
     * reconciled is found here the first time one of its members is tagged.
     */
    Set<OwnerRef> taggregateContainersOf(Session session, Class<?> childType, UUID childId) {
        Set<OwnerRef> owners = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (!edge.taggregate() || !edge.childType().isAssignableFrom(childType)) {
                continue;
            }
            collectHql(session, owners, edge, parentsHql(edge, childType), childId);
        }
        return owners;
    }

    /**
     * Every member {@code (type, id)} currently held by {@code (containerType, containerId)} through its
     * {@code @Taggregate} fields -- the forward direction, and what lets a recompute be a query rather than
     * a walk of a loaded object's lazy collections.
     *
     * <p>⚠️ That distinction is a defect class, not a preference: walking the object required the caller to
     * hold an open session, and an entity read outside one threw {@code LazyInitializationException} the
     * moment the aggregate touched a {@code @ManyToMany}. A query has no such requirement.
     */
    Set<OwnerRef> taggregateMembersOf(Session session, Class<?> containerType, UUID containerId) {
        Set<OwnerRef> members = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (!edge.taggregate() || !edge.parentType().isAssignableFrom(containerType)) {
                continue;
            }
            String parentId = EntityReflection.idField(edge.parentType()).getName();
            String childIdField = EntityReflection.idField(edge.childType()).getName();
            String hql = switch (edge.kind()) {
                case NATIVE_COLLECTION -> "select c." + childIdField + " from " + edge.parentType().getName()
                        + " p join p." + edge.fieldName() + " c where p." + parentId + " = :containerId";
                case NATIVE_SINGULAR -> "select p." + edge.fieldName() + "." + childIdField + " from "
                        + edge.parentType().getName() + " p where p." + parentId + " = :containerId"
                        + " and p." + edge.fieldName() + " is not null";
            };
            for (UUID memberId : session.createQuery(hql, UUID.class)
                    .setParameter("containerId", containerId).getResultList()) {
                members.add(new OwnerRef(edge.childType(), memberId));
            }
        }
        return members;
    }

    /**
     * The declared {@code @Taggregate} edges, for a backend that must issue its own store-native query
     * rather than HQL.
     *
     * <p>This is the reuse boundary that keeps one notion of containment across three stores: the
     * <em>declaration</em> -- which field of which type holds what -- is read once, here, by reflection that
     * has nothing to do with any database. Only the traversal differs per backend (HQL, Cypher, a reference
     * array). A backend that re-derived the declaration itself would be the second implementation OMI-304
     * exists to prevent.
     */
    List<Edge> taggregateEdges() {
        return edges.stream().filter(Edge::taggregate).toList();
    }

    /** Every type that declares at least one {@code @Taggregate} field -- the containers {@code rebuild} has
     *  to visit, since a repair pass cannot be driven by a pending set that direct SQL never wrote to. */
    Set<Class<?>> taggregateContainerTypes() {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Edge edge : edges) {
            if (edge.taggregate()) {
                types.add(edge.parentType());
            }
        }
        return types;
    }

    /** Every persisted id of {@code containerType} -- {@code rebuild}'s work list for one container type. */
    List<UUID> idsOf(Session session, Class<?> containerType) {
        String idField = EntityReflection.idField(containerType).getName();
        return session.createQuery(
                "select p." + idField + " from " + containerType.getName() + " p", UUID.class).getResultList();
    }

    /** The parents-of-child HQL both {@link #containersOf} and {@link #taggregateContainersOf} issue -- one
     *  query shape, so the two annotations cannot drift into two different notions of containment. */
    private static String parentsHql(Edge edge, Class<?> childType) {
        String parentId = EntityReflection.idField(edge.parentType()).getName();
        String childIdField = EntityReflection.idField(childType).getName();
        return switch (edge.kind()) {
            case NATIVE_COLLECTION -> "select p." + parentId + " from " + edge.parentType().getName() + " p"
                    + " join p." + edge.fieldName() + " c where c." + childIdField + " = :childId";
            case NATIVE_SINGULAR -> "select p." + parentId + " from " + edge.parentType().getName() + " p"
                    + " where p." + edge.fieldName() + "." + childIdField + " = :childId";
        };
    }

    private static void collectHql(Session session, Set<OwnerRef> owners, Edge edge, String hql, UUID childId) {
        for (UUID ownerId : session.createQuery(hql, UUID.class).setParameter("childId", childId).getResultList()) {
            owners.add(new OwnerRef(edge.parentType(), ownerId));
        }
    }



    private static Class<?> elementType(Field field, int index) {
        if (field.getGenericType() instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (index < arguments.length && arguments[index] instanceof Class<?> type) {
                return type;
            }
        }
        return null;
    }

    /** A summary owner, as the queue and the drain both name one. */
    record OwnerRef(Class<?> ownerType, UUID ownerId) {
    }
}
