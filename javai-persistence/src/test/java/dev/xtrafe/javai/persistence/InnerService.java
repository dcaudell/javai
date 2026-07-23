package dev.xtrafe.javai.persistence;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inner half of the propagation tests (OMI-146): a separate Spring bean, so calls into it go through the
 * transaction proxy. A method on the <em>same</em> bean would be invoked directly, bypassing the proxy
 * entirely, and every propagation assertion here would silently test nothing.
 */
class InnerService {

    private final SpringTransactionalIntegrationTest.SpringTxRecordRepository repository;

    InnerService(SpringTransactionalIntegrationTest.SpringTxRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void requiresNewWrite(String label) {
        repository.save(new TestTxRecord(label));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void mandatoryWrite(String label) {
        repository.save(new TestTxRecord(label));
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    void supportsWrite(String label) {
        repository.save(new TestTxRecord(label));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void notSupportedWrite(String label) {
        repository.save(new TestTxRecord(label));
    }

    @Transactional(propagation = Propagation.NEVER)
    void neverWrite(String label) {
        repository.save(new TestTxRecord(label));
    }

    @Transactional(propagation = Propagation.NESTED)
    void nestedWriteThenFail(String label) {
        repository.save(new TestTxRecord(label));
        throw new IllegalStateException("nested body fails, rolling back to its savepoint");
    }
}
