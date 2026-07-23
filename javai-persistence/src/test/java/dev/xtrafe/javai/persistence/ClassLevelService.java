package dev.xtrafe.javai.persistence;

import org.springframework.transaction.annotation.Transactional;

/**
 * The same behavior declared with a <em>class-level</em> {@code @Transactional} instead of a method-level
 * one. Spring resolves the annotation's placement long before JavAI is involved, so this must be
 * indistinguishable from {@link OuterService} -- which is worth an actual test rather than an assumption,
 * since "works transparently" would be a hollow claim if it held only for the form the implementer happened
 * to try first.
 */
@Transactional
class ClassLevelService {

    private final SpringTransactionalIntegrationTest.SpringTxRecordRepository repository;

    ClassLevelService(SpringTransactionalIntegrationTest.SpringTxRecordRepository repository) {
        this.repository = repository;
    }

    void twoWritesThenFail(String label) {
        repository.save(new TestTxRecord(label));
        repository.save(new TestTxRecord(label));
        throw new IllegalStateException("fails after two successful repository writes");
    }
}
