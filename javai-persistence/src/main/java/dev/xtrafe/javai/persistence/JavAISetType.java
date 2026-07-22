package dev.xtrafe.javai.persistence;

import dev.xtrafe.javai.model.JavAISet;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.CollectionClassification;
import org.hibernate.persister.collection.CollectionPersister;
import org.hibernate.usertype.UserCollectionType;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * OMI-142: the {@code UserCollectionType} that makes Hibernate substitute
 * {@link PersistentJavAISet} instead of its own {@code PersistentBag}. In the real implementation this
 * would be attached automatically by the backend's generated mapping, so a consumer never names it.
 */
public class JavAISetType implements UserCollectionType {

    @Override
    public CollectionClassification getClassification() {
        return CollectionClassification.SET;
    }

    @Override
    public Class<?> getCollectionClass() {
        return JavAISet.class;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PersistentCollection<?> instantiate(
            SharedSessionContractImplementor session, CollectionPersister persister) {
        return new PersistentJavAISet<>(session);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public PersistentCollection<?> wrap(SharedSessionContractImplementor session, Object collection) {
        return new PersistentJavAISet<>(session, (java.util.Set) collection);
    }

    @Override
    public Iterator<?> getElementsIterator(Object collection) {
        return ((Collection<?>) collection).iterator();
    }

    @Override
    public boolean contains(Object collection, Object entity) {
        return ((Collection<?>) collection).contains(entity);
    }

    @Override
    public Object indexOf(Object collection, Object entity) {
        return null; // a Set has no index
    }

    /**
     * Each element must be translated through Hibernate's {@code copyCache} to its <em>merged</em>
     * counterpart -- {@code target.addAll(original)} looks right but is wrong: it puts the detached
     * originals into the managed collection, so flush-time cascade then sees two instances sharing one id and
     * throws {@code NonUniqueObjectException}. (Observed exactly that in the first run of this spike.)
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object replaceElements(Object original, Object target, CollectionPersister persister,
            Object owner, Map copyCache, SharedSessionContractImplementor session) {
        Collection targetCollection = (Collection) target;
        targetCollection.clear();
        for (Object element : (Collection) original) {
            targetCollection.add(
                    persister.getElementType().replace(element, null, session, owner, copyCache));
        }
        return target;
    }

    @Override
    public Object instantiate(int anticipatedSize) {
        return new dev.xtrafe.javai.model.JavAILinkedHashSet<>();
    }
}
