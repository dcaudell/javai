package dev.xtrafe.javai.persistence;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * Finds the Hibernate {@link Session} a caller's Spring transaction has already bound to this thread, so a
 * {@code JavAIRepository} call made inside a {@code @Transactional} method participates in that unit of work
 * instead of opening its own session and committing independently (OMI-146).
 *
 * <p><b>Only load this class when spring-orm is actually present.</b> spring-orm is an {@code <optional>}
 * dependency of this module, so {@link #isAvailable()} -- which touches no spring-orm type itself -- is the
 * gate every caller must pass through first. A non-Spring application never reaches the methods below, and
 * therefore never resolves {@link EntityManagerHolder}.
 *
 * <p><b>Why it scans the bound resources rather than looking one up by key.</b> The obvious implementation,
 * {@code TransactionSynchronizationManager.getResource(sessionFactory)}, misses the common case: Spring's
 * {@code JpaTransactionManager} binds its {@code EntityManagerHolder} under the {@code EntityManagerFactory}
 * <em>bean</em>, which for {@code LocalContainerEntityManagerFactoryBean} is a proxy, while what JavAI holds
 * (via {@code JavAIPersistenceConfig.Builder.sessionFactory}) is the native {@code SessionFactory} the
 * consumer unwrapped from it. Those are different objects, so a keyed lookup returns nothing even though the
 * transaction is right there. Scanning every bound resource and comparing the <em>session's own</em>
 * {@code getSessionFactory()} to ours identifies the match by the only identity that actually matters, and
 * covers {@code HibernateTransactionManager}'s {@code SessionHolder} for free, since that holder's session
 * answers the same question.
 *
 * <p><b>Never returns a session for a transaction that isn't really one.</b> The check requires an actual
 * active transaction, not merely active synchronization -- Spring keeps synchronizations active for
 * {@code PROPAGATION_NOT_SUPPORTED}/{@code PROPAGATION_NEVER} and for a read-only suspend, where joining
 * would mean writing through a session whose transaction the caller deliberately stepped out of.
 */
final class SpringManagedSessions {

    private static final boolean SPRING_ORM_PRESENT = isClassPresent("org.springframework.orm.jpa.EntityManagerHolder")
            && isClassPresent("org.springframework.transaction.support.TransactionSynchronizationManager");

    private SpringManagedSessions() {
    }

    /** Whether the Spring classes this bridge needs are on the classpath at all. Touches no spring-orm type,
     *  so it is safe to call from a non-Spring application. */
    static boolean isAvailable() {
        return SPRING_ORM_PRESENT;
    }

    /**
     * The session bound to this thread by an active Spring transaction over {@code factory}, or {@code null}
     * if there is no such transaction (or it belongs to a different factory). Callers must have checked
     * {@link #isAvailable()} first.
     */
    static Session current(SessionFactory factory) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }
        for (Map.Entry<Object, Object> bound : TransactionSynchronizationManager.getResourceMap().entrySet()) {
            Session session = sessionOf(bound.getValue());
            if (session != null && session.getSessionFactory() == factory) {
                return session;
            }
        }
        return null;
    }

    /**
     * The Hibernate session inside one of Spring's bound resource holders, or {@code null} if this resource
     * isn't a JPA/Hibernate one at all (a bound {@code DataSource} connection holder, say).
     *
     * <p>One {@code instanceof} covers both transaction managers: {@code JpaTransactionManager} binds an
     * {@link EntityManagerHolder}, and {@code HibernateTransactionManager} binds a
     * {@code org.springframework.orm.jpa.hibernate.SessionHolder}, which <em>extends</em>
     * {@code EntityManagerHolder} (verified against spring-orm 7.0.8, not assumed). A {@code SessionHolder}
     * built around a {@code StatelessSession} has no {@code EntityManager} to hand back, hence the
     * defensive null/failure handling rather than a bare {@code getEntityManager()}.
     */
    private static Session sessionOf(Object resource) {
        if (!(resource instanceof EntityManagerHolder holder)) {
            return null;
        }
        EntityManager entityManager;
        try {
            entityManager = holder.getEntityManager();
        } catch (RuntimeException e) {
            return null; // a stateless-session holder, or one not yet populated: nothing to join
        }
        if (entityManager instanceof Session session) {
            return session;
        }
        return entityManager == null ? null : entityManager.unwrap(Session.class);
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, SpringManagedSessions.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
