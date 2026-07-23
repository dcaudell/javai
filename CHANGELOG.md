# Changelog

Notable changes to JavAI Extensions, newest first. Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
loosely and [Semantic Versioning](https://semver.org/spec/v2.0.0.html) exactly.

This file starts at 0.1.5. Releases before it (0.1.0 through 0.1.4) are described by their git history and
their GitHub releases; nothing is reconstructed here after the fact, since a changelog written from memory is
worth less than the commit log it would be guessing from.

Each entry names the module it affects, because this repository releases all nine modules together at one
version -- a given release usually changes only one or two of them.

## [Unreleased]

### Changed -- BREAKING

- **`javai-persistence` (Postgres only): the default physical naming strategy is now
  `CamelCaseToUnderscoresNamingStrategy`.** A camel-cased field maps to a snake_cased column --
  `emailVerified` becomes `email_verified`, where releases up to 0.1.4 produced `emailverified`. Table names
  follow the same rule, so an entity class `TestCrew` now maps to `test_crew`, and any join table derived
  from it moves with it. This matches Spring Boot's default and ordinary SQL convention.

  **Why:** pointing a `JavAIRepository` at a table some other tool already created under the conventional
  naming produced a table carrying *both* conventions at once — `hbm2ddl=update` added JavAI's
  `emailverified` alongside the existing `email_verified` rather than recognizing it, and the subsequent
  insert populated JavAI's column while leaving the original `NOT NULL` one null. Adopting JavAI for an
  entity with an existing table therefore looked like a silent column rename against live data.

  **Who is affected:** any deployment whose Postgres schema was created by JavAI at 0.1.4 or earlier *and*
  contains an entity with a multi-word field or class name. A single-word schema (`title`, `body`, `article`)
  is unaffected — the two strategies agree there.

  **What to do**, whichever fits:
  - *Keep the existing schema as-is:* pin the old behavior explicitly.
    ```java
    JavAIPersistenceConfig.builder()
        .backend(POSTGRES)
        .physicalNamingStrategy(new PhysicalNamingStrategyStandardImpl())   // pre-0.1.5 naming
        // ...
        .build();
    ```
  - *Adopt the new naming:* rename the affected columns/tables (`ALTER TABLE ... RENAME COLUMN
    emailverified TO email_verified`) before deploying. `hbm2ddl=update` will otherwise add the new columns
    beside the old ones and leave the old data stranded — it never renames, and never drops.

  Neo4j and MongoDB are unaffected: both classify fields by declared type and have no equivalent of JPA
  column naming.

### Added

- **`javai-persistence` (Postgres): repository calls now join a caller's transaction (OMI-146).** Previously
  every call opened its own Hibernate `Session` and committed independently, so several calls could never
  form one atomic unit of work — a Spring `@Transactional` service method composing four repositories got
  four transactions, and a failure on the last left the first three permanently committed.

  A call now runs on the caller's session when one is active, and opens its own only when there isn't:
  - **Spring `@Transactional`**, with no JavAI-specific API at the call site. Requires that JavAI share the
    application's own factory (`Builder.sessionFactory(emf.unwrap(SessionFactory.class))`), since that is
    what there is to join. Works under `JpaTransactionManager` and `HibernateTransactionManager`, at class
    and method level, and honors the annotation's attributes — `isolation`, `readOnly`,
    `rollbackFor`/`noRollbackFor`, and the propagation modes, including correctly *not* joining under
    `REQUIRES_NEW`/`NOT_SUPPORTED`, where the caller has deliberately stepped outside the transaction.
  - **`JavAIPI.inTransaction(config, body)`** (new), for callers not running under Spring: every repository
    call in the body shares one session and commits, or rolls back, once. Nesting joins the outer body
    (`PROPAGATION_REQUIRED` semantics) rather than opening a second transaction. Thread-bound.

  Vector rows are written on the caller's own connection before their commit, so they now roll back with the
  caller's transaction rather than being committed separately.

  Behavior is unchanged when there is no ambient transaction, which is every pre-0.1.5 usage. Neo4j and
  MongoDB are unaffected: each call remains its own unit of work, and `inTransaction` throws there rather
  than implying an atomicity it doesn't provide. Two documented edges: a write inside `readOnly = true` fails
  loudly (Postgres rejects the INSERT), and `PROPAGATION_NESTED` is unsupported under `JpaTransactionManager`
  because Spring's Hibernate JPA dialect exposes no savepoint manager — Spring refuses it before JavAI is
  involved.

  Adds `org.springframework:spring-orm` as an **optional** dependency: it is what holds the resource holders
  a Spring transaction binds. Optional dependencies are not transitive, and the bridge class is only loaded
  after a runtime presence check, so a non-Spring consumer's classpath is unaffected.
- **`javai-persistence`: `JavAIPersistenceConfig.Builder.physicalNamingStrategy(PhysicalNamingStrategy)`** —
  overrides the naming strategy applied to the `SessionFactory` this module builds, including pinning the
  pre-0.1.5 behavior above.
- **`javai-persistence`: `JavAIPersistenceConfig.Builder.hibernateProperty(String, Object)` and
  `.hibernateProperties(Map)`** — a general passthrough for any Hibernate setting this builder exposes no
  typed method for. Applied *after* the settings this module sets itself (`jakarta.persistence.jdbc.*`,
  `hibernate.hbm2ddl.auto`), so an explicitly-named key wins over JavAI's own default. Inert when
  `Builder.sessionFactory(...)` supplies a factory this module didn't build.

  Together these close a real gap: before, a consumer who needed correct column naming had to supply their
  own `SessionFactory`, which skips the two mapping-time hooks (`attachJavAICollectionTypes`,
  `buildAutoTransientOverrideXml`) that JavAI collection fields depend on — so correct naming and collection
  support were mutually exclusive.

[Unreleased]: https://github.com/dcaudell/javai/compare/v0.1.4...HEAD
