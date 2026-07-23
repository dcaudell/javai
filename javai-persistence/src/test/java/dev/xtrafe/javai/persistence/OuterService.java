package dev.xtrafe.javai.persistence;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;

/**
 * Method-level {@code @Transactional} in every shape the annotation offers, each method doing its work
 * purely through a {@code JavAIRepository} -- the point being that none of these methods contains a single
 * JavAI-specific transaction call (OMI-146).
 */
class OuterService {

    private final SpringTransactionalIntegrationTest.SpringTxRecordRepository repository;
    private final InnerService inner;
    private final SessionFactory sessionFactory;

    OuterService(SpringTransactionalIntegrationTest.SpringTxRecordRepository repository, InnerService inner,
            SessionFactory sessionFactory) {
        this.repository = repository;
        this.inner = inner;
        this.sessionFactory = sessionFactory;
    }

    /** The proxied inner bean, for tests that need to call it with no surrounding transaction. */
    InnerService inner() {
        return inner;
    }

    @Transactional
    void twoWrites(String label) {
        repository.save(new TestTxRecord(label));
        repository.save(new TestTxRecord(label));
    }

    @Transactional
    void twoWritesThenFail(String label) {
        repository.save(new TestTxRecord(label));
        repository.save(new TestTxRecord(label));
        throw new IllegalStateException("fails after two successful repository writes");
    }

    /** Two separate repository calls: the second can only see the first's work if it joined its session. */
    @Transactional
    boolean writeThenReadBack(String label) {
        TestTxRecord saved = repository.save(new TestTxRecord(label));
        return repository.findById(saved.getId()).isPresent();
    }

    @Transactional
    void writeThenRequiresNewThenFail(String outerLabel, String innerLabel) {
        repository.save(new TestTxRecord(outerLabel));
        inner.requiresNewWrite(innerLabel);
        throw new IllegalStateException("outer fails; the REQUIRES_NEW work above has already committed");
    }

    @Transactional
    void callMandatoryWithinTransaction(String label) {
        inner.mandatoryWrite(label);
    }

    @Transactional
    void writeThenNotSupportedThenFail(String outerLabel, String innerLabel) {
        repository.save(new TestTxRecord(outerLabel));
        inner.notSupportedWrite(innerLabel);
        throw new IllegalStateException("outer fails; the NOT_SUPPORTED work ran outside this transaction");
    }

    @Transactional
    void callNeverWithinTransaction(String label) {
        inner.neverWrite(label);
    }

    @Transactional
    void writeThenNestedFailureIsCaught(String outerLabel, String innerLabel) {
        repository.save(new TestTxRecord(outerLabel));
        try {
            inner.nestedWriteThenFail(innerLabel);
        } catch (IllegalStateException expected) {
            // Swallowed deliberately: PROPAGATION_NESTED rolls back to its savepoint, and this method's own
            // work must survive that. Rethrowing would roll the whole transaction back and prove nothing.
        }
    }

    /**
     * Writes through JavAI, then reports the isolation level of the connection behind the session JavAI just
     * used. Reads it via {@code SpringManagedSessions} -- the very lookup the backend performs -- so this
     * asserts the connection JavAI actually writes through, not merely that Spring configured something
     * somewhere.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    int writeUnderSerializableAndReportIsolation(String label) {
        repository.save(new TestTxRecord(label));
        Session joined = SpringManagedSessions.current(sessionFactory);
        if (joined == null) {
            throw new IllegalStateException("expected to find the Spring-managed session JavAI just wrote through");
        }
        return joined.doReturningWork(Connection::getTransactionIsolation);
    }

    @Transactional(readOnly = true)
    long countUnderReadOnly(String label) {
        return repository.findAll().stream().filter(record -> label.equals(record.getLabel())).count();
    }

    @Transactional(readOnly = true)
    void writeUnderReadOnly(String label) {
        repository.save(new TestTxRecord(label));
    }

    @Transactional(timeout = 1)
    void writeSleepThenWriteWithTimeout(String label) {
        repository.save(new TestTxRecord(label));
        try {
            Thread.sleep(1_500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        repository.save(new TestTxRecord(label));
    }

    @Transactional
    void writeThenThrowChecked(String label) throws SpringTransactionalIntegrationTest.TestCheckedException {
        repository.save(new TestTxRecord(label));
        throw new SpringTransactionalIntegrationTest.TestCheckedException("checked: no rollback by default");
    }

    @Transactional(rollbackFor = Exception.class)
    void writeThenThrowCheckedWithRollbackFor(String label)
            throws SpringTransactionalIntegrationTest.TestCheckedException {
        repository.save(new TestTxRecord(label));
        throw new SpringTransactionalIntegrationTest.TestCheckedException("checked, but rollbackFor covers it");
    }

    @Transactional(noRollbackFor = IllegalStateException.class)
    void writeThenThrowWithNoRollbackFor(String label) {
        repository.save(new TestTxRecord(label));
        throw new IllegalStateException("runtime, but noRollbackFor keeps the write");
    }
}
