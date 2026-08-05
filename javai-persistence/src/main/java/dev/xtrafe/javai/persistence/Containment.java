package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.annotations.Summary;
import jakarta.persistence.Entity;
import org.hibernate.Session;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
 * A {@code @Summary} collection is stored one of two ways on this backend, and the reverse lookup differs:
 * a <b>natively mapped</b> association (a plain JDK collection, or an interface-typed JavAI collection with
 * {@code @OneToMany}/{@code @ManyToMany}) lives in Hibernate's own join table and is reached with HQL, while
 * a <b>concrete-typed</b> JavAI collection lives in this backend's {@code javai_collection_members} and is
 * reached with SQL against that table. A singular {@code @Summary} reference is HQL either way. Missing
 * either shape would make the fix silently partial for entities that happen to use it.
 */
final class Containment {

    /**
     * One declared containment edge: {@code parentType.fieldName} can hold {@code childType}.
     *
     * <p>{@code summary} records whether the field carries {@code @Summary}, because the two consumers want
     * different subsets. Summary recomputation cares only about annotated fields; detaching an entity before
     * deleting it cares about <em>every</em> mapped collection, since a foreign key does not check whether
     * the association was interesting enough to be summarised.
     */
    record Edge(Class<?> parentType, String fieldName, Class<?> childType, Kind kind, boolean summary) {
    }

    enum Kind {
        /** Hibernate owns the association -- reachable by HQL through the mapped field. */
        NATIVE_COLLECTION,
        /** A singular reference ({@code @OneToOne}/{@code @ManyToOne}). */
        NATIVE_SINGULAR,
        /** This backend owns the membership rows, in {@code javai_collection_members}. */
        JAVAI_COLLECTION
    }

    private final List<Edge> edges;

    private Containment(List<Edge> edges) {
        this.edges = edges;
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
                Class<?> declared = field.getType();
                if (Map.class.isAssignableFrom(declared) || Collection.class.isAssignableFrom(declared)) {
                    Class<?> element = elementType(field, Map.class.isAssignableFrom(declared) ? 1 : 0);
                    if (element != null && element.isAnnotationPresent(Entity.class)) {
                        edges.add(new Edge(parentType, field.getName(), element,
                                isNativelyMapped(field) ? Kind.NATIVE_COLLECTION : Kind.JAVAI_COLLECTION,
                                summary));
                    }
                } else if (summary && declared.isAnnotationPresent(Entity.class)) {
                    // Singular references are collected only when they are @Summary. A to-one is not
                    // something an entity can be *detached* from on the way to being deleted -- nulling
                    // someone else's field is a change to their data, not a cleanup -- so the delete path
                    // has no use for the rest, and letting the foreign key refuse is the honest outcome.
                    edges.add(new Edge(parentType, field.getName(), declared, Kind.NATIVE_SINGULAR, true));
                }
            }
        }
        return new Containment(List.copyOf(edges));
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
            String parentId = EntityReflection.idField(edge.parentType()).getName();
            String childIdField = EntityReflection.idField(childType).getName();
            switch (edge.kind()) {
                case NATIVE_COLLECTION -> collectHql(session, owners, edge,
                        "select p." + parentId + " from " + edge.parentType().getName() + " p"
                                + " join p." + edge.fieldName() + " c where c." + childIdField + " = :childId",
                        childId);
                case NATIVE_SINGULAR -> collectHql(session, owners, edge,
                        "select p." + parentId + " from " + edge.parentType().getName() + " p"
                                + " where p." + edge.fieldName() + "." + childIdField + " = :childId", childId);
                case JAVAI_COLLECTION -> collectMembershipRows(session, owners, edge, childType, childId);
            }
        }
        return owners;
    }

    /** The owners currently holding {@code (childType, childId)} through one specific edge. Same lookup
     *  {@link #containersOf} performs, exposed per-edge because the delete path needs to know <em>which</em>
     *  field to remove the child from, not merely that some field does. */
    Set<OwnerRef> ownersHolding(Session session, Edge edge, Class<?> childType, UUID childId) {
        Set<OwnerRef> owners = new LinkedHashSet<>();
        String parentId = EntityReflection.idField(edge.parentType()).getName();
        String childIdField = EntityReflection.idField(childType).getName();
        switch (edge.kind()) {
            case NATIVE_COLLECTION -> collectHql(session, owners, edge,
                    "select p." + parentId + " from " + edge.parentType().getName() + " p"
                            + " join p." + edge.fieldName() + " c where c." + childIdField + " = :childId",
                    childId);
            case NATIVE_SINGULAR -> collectHql(session, owners, edge,
                    "select p." + parentId + " from " + edge.parentType().getName() + " p"
                            + " where p." + edge.fieldName() + "." + childIdField + " = :childId", childId);
            case JAVAI_COLLECTION -> collectMembershipRows(session, owners, edge, childType, childId);
        }
        return owners;
    }

    private static void collectHql(Session session, Set<OwnerRef> owners, Edge edge, String hql, UUID childId) {
        for (UUID ownerId : session.createQuery(hql, UUID.class).setParameter("childId", childId).getResultList()) {
            owners.add(new OwnerRef(edge.parentType(), ownerId));
        }
    }

    /** The {@code javai_collection_members} half. Filtered by {@code field_name} as well as member, so a
     *  container holding the same entity in both a {@code @Summary} field and an ordinary one is credited
     *  only for the {@code @Summary} one. */
    private static void collectMembershipRows(
            Session session, Set<OwnerRef> owners, Edge edge, Class<?> childType, UUID childId) {
        session.doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT owner_id FROM javai_collection_members WHERE owner_type = ? AND field_name = ?"
                            + " AND member_type = ? AND member_id = ?")) {
                statement.setString(1, edge.parentType().getName());
                statement.setString(2, edge.fieldName());
                statement.setString(3, childType.getName());
                statement.setObject(4, childId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        owners.add(new OwnerRef(edge.parentType(), (UUID) rows.getObject(1)));
                    }
                }
            }
        });
    }

    /**
     * A {@code @Summary} collection field is Hibernate's to map when it carries an association annotation;
     * otherwise this backend stores its membership itself. Mirrors the rule
     * {@code RepositoryBackendHibernatePostgres.isJavAICollectionField} enforces at registration, read from
     * the same annotations rather than re-derived from the field's type.
     */
    private static boolean isNativelyMapped(Field field) {
        return field.isAnnotationPresent(jakarta.persistence.OneToMany.class)
                || field.isAnnotationPresent(jakarta.persistence.ManyToMany.class)
                || field.isAnnotationPresent(jakarta.persistence.ElementCollection.class);
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
