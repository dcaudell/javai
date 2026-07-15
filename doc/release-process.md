# Release process

How code moves from a feature branch to a tagged, published Maven Central release, and the one-time
account/key setup the automation in `.github/workflows/` depends on but cannot do on its own.

## The flow, end to end

```
feature/my-thing --PR--> Dev --PR--> main --tag vX.Y.Z--> publish.yml --> Maven Central
                    |             |
                 ci.yml        ci.yml
                (test)   (test + release-readiness)
```

1. **Feature work branches from `Dev`.** Open a PR back to `Dev`. `.github/workflows/ci.yml`'s `test` job
   runs automatically (`mvn test` from the repo root -- every module except the standalone
   `e2e-client-test` project, which is never part of this reactor build). It excludes two categories of
   test by design (see the root `pom.xml`'s `javai.excludedTestGroups` property): hand-run performance
   benchmarks, and anything needing a real LLM/embedding model in a container (currently just
   `CortexOllamaRealContainerTest`). Everything else -- including `javai-persistence`'s real Postgres/Neo4j
   Testcontainers tests -- runs, since those need Docker but never a model.
2. **Once `Dev` is ready to ship, open a PR from `Dev` to `main`.** The same `test` job runs, plus a second
   `release-readiness` job: it confirms the version isn't a `-SNAPSHOT` and that `mvn -Prelease verify`
   (javadoc + sources jars for every module, GPG signing skipped) succeeds -- catching a broken javadoc
   comment or similar release-profile problem before it ever reaches a tag. Neither job needs any secret to
   run.
3. **Merge the PR.** `main` now has a non-SNAPSHOT version and a clean release-profile build behind it.
4. **Tag the merge commit**, matching the version just merged, e.g. `git tag vX.Y.Z && git push origin
   vX.Y.Z` (locally, or via a GitHub Release UI that creates the tag for you). This triggers
   `.github/workflows/publish.yml`. See `doc/spec/versioning.md` for exactly where `X.Y.Z` needs to be
   bumped before this step.
5. **`publish.yml` pauses for manual approval** (the `maven-central-release` GitHub Environment -- see
   below), then runs the full test suite again, builds every module's jar/sources/javadoc, GPG-signs all of
   them, and uploads a release bundle to the Sonatype Central Portal. It stops there (`autoPublish: false`
   in the release profile) rather than auto-publishing.
6. **One more manual step, in Central's own web UI**: log into <https://central.sonatype.com>, find the
   uploaded deployment, and click "Publish." This is a second, deliberate checkpoint on an action that's
   essentially permanent once live -- worth keeping until this flow has been run a few times, at which
   point flipping `autoPublish` to `true` in the root `pom.xml`'s release profile removes it.
7. Propagation to `search.maven.org`/most mirrors typically takes 15-30 minutes after that click.

## What I can't do for you

None of the following can be done by an AI agent acting on this repository -- they require your own
identity, payment-free but personal account creation, and control of infrastructure (DNS, a GPG key) I have
no access to.

### 1. Create a Sonatype Central Portal account and verify the `io.github.dcaudell` namespace

1. Create an account at <https://central.sonatype.com> (free) -- sign in with (or link) your `dcaudell`
   GitHub account, since that's what step 2 verifies against.
2. Request the namespace `io.github.dcaudell`. Because this is an `io.github.<username>` namespace, Central
   verifies it automatically against your GitHub account -- no DNS TXT record, no domain ownership proof,
   and no dependency on controlling `xtrafe.dev` or any other custom domain. This is why the project's
   `groupId` is `io.github.dcaudell` rather than a reverse-DNS `dev.xtrafe.javai`: the latter would require
   DNS control over `xtrafe.dev`, which isn't available here.
3. Once verified, generate a **user token** from your Central Portal account (Account -> Generate User
   Token). This gives you a username/password pair that is *not* your actual account password -- this is
   what becomes the `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` secrets below.

### 2. Generate a GPG signing key

Every artifact Central publishes must be signed. If you don't already have a GPG key you're happy using for
this:

```bash
gpg --full-generate-key
# RSA and RSA, 4096 bits, key does not expire (or a long expiry -- your call), your name + a real email.

gpg --list-secret-keys --keyid-format=long
# Note the key ID (the part after rsa4096/) from the output.

gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_KEY_ID>
# Central checks signatures against public keyservers -- your public key must be published to at least one.

gpg --armor --export-secret-keys <YOUR_KEY_ID> > private-key.asc
# The file publish.yml's GitHub secret needs the contents of. Delete this file once it's in GitHub
# secrets -- don't leave a plaintext private key sitting on disk.
```

### 3. Add the GitHub repository secrets

Settings -> Secrets and variables -> Actions -> New repository secret, four of them:

| Secret name | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | The username half of the Central Portal user token from step 1.3 |
| `MAVEN_CENTRAL_PASSWORD` | The password half of that same token |
| `MAVEN_GPG_PRIVATE_KEY` | The full contents of `private-key.asc` from step 2, pasted as-is |
| `MAVEN_GPG_PASSPHRASE` | The passphrase you set when generating the key |

### 4. Create the `maven-central-release` GitHub Environment with a required reviewer

Settings -> Environments -> New environment -> name it exactly `maven-central-release` (matching
`publish.yml`) -> under "Deployment protection rules," add yourself (or whoever should approve a release)
as a **required reviewer**. Without this, `publish.yml` will run straight through on every tag push with no
human checkpoint at all -- this is the step that turns "push a tag" into "push a tag, then approve it."

### 5. Branch protection: make CI failures actually block merges

A workflow file alone doesn't block a merge -- GitHub only enforces that if you tell it to, per branch:

Settings -> Branches -> Add branch protection rule.

**For `Dev`:**
- Branch name pattern: `Dev`
- Require a pull request before merging (recommended)
- Require status checks to pass before merging -> search for and add: **`Build and test (excludes e2e / real-model tests)`**

**For `main`:**
- Branch name pattern: `main`
- Require a pull request before merging (recommended)
- Require status checks to pass before merging -> add **both**:
  - `Build and test (excludes e2e / real-model tests)`
  - `Release-profile build (javadoc/sources, no signing, no deploy)`
- Consider also enabling "Do not allow bypassing the above settings" and disabling force-pushes to `main`.

(The check names above come from each job's `name:` field in `.github/workflows/ci.yml` -- if you rename a
job there, update the required check name here to match, or GitHub will show it as "expected, never
reported" and block merges forever.)

## Verifying everything works before the first real tag

**Already done** -- this flow was proven end to end before the `v0.1.0` release and has published
successfully since (`v0.1.0`, `v0.1.1`). Kept below as a reference runbook in case the pipeline itself
changes significantly enough to warrant re-proving it with another throwaway version:

1. Bump the version to something obviously disposable (e.g. `X.Y.Z-rc1`) on a scratch branch, open a PR to
   `main`, confirm both CI checks go green.
2. Merge, tag `vX.Y.Z-rc1`, push the tag, approve the environment, confirm `publish.yml` succeeds all the
   way through the Central Portal upload.
3. In Central's web UI, you can **drop** (not publish) that test deployment once you've confirmed it
   uploaded correctly -- there's no need to actually make a throwaway version public.
4. Once that's proven out, do the real thing with the actual version.

## Everyday development (no publishing involved)

Nothing above matters for ordinary feature work. Branch from `Dev`, PR back to `Dev`, `ci.yml`'s `test` job
runs automatically, merge once it's green. The release machinery only activates on a PR targeting `main` or
a `v*.*.*` tag push.
