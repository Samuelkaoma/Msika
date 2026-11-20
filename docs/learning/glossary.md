# Glossary

Plain-words definitions. New terms are added when a lesson first uses them.

| Term | Meaning |
|---|---|
| **ngwee** | 1/100 of a kwacha. We store all money as a whole number of ngwee. |
| **Ledger** | The append-only list of money events (entries). |
| **Provenance** | How sure we are of an entry: `VERIFIED`, `DEPOSITED` or `DECLARED`. |
| **Reversal** | A new entry that cancels an earlier one. We never edit or delete the original. |
| **Seal** | The signed fingerprint of one council's day of entries, chained to the previous day. |
| **Merkle tree** | A way to fingerprint many items into one hash so any change to any item is detectable. |
| **Port** | An interface in `msika-core` describing what we need from the outside world. |
| **Adapter** | Code in `msika-api` that implements a port by calling a real or simulated system. |
| **Idempotent** | Doing it twice has the same effect as doing it once. Needed because messages can arrive twice. |
| **RLS (row-level security)** | A Postgres feature that hides rows a user is not allowed to see, even if a query forgets a `WHERE`. |
| **Flyway migration** | A numbered SQL file that changes the database structure. Never edited after it is shared. |
| **Council** | A local authority. The tenant: each council only sees its own data. |
| **Levy** | The daily fee a trader pays the council for a stall. |
| **Stall code** | The stall's identifier, like `SOW-B-114`. |
| **Card** | One unit of work in `docs/ROADMAP.md`, like `M-01`. |
| **Monorepo** | One git repository holding several projects. |
| **Module** | One sub-project of the Gradle build, such as `msika-core`. |
| **Gradle wrapper** | The `gradlew` script that downloads the exact Gradle version the project needs. |
| **BOM (bill of materials)** | A list of library versions known to work together. Spring Boot provides one. |
| **Spring Boot** | The Java framework that runs our web services. |
| **Actuator** | Spring Boot's built-in endpoints, such as `/health`. |
| **Health endpoint** | A URL that answers "is this app alive?". |
| **pnpm workspace** | Several JavaScript packages in one repo, built together. |
| **Pinning** | Writing an exact version (`4.2.0`) instead of a range (`^4.2.0`). |
| **Git flow** | The branching convention: `main` for releases, `develop` for integration, one `feature/` branch per card. |
| **Quality gate** | The set of automatic checks a change must pass. Here: `scripts/check`. |
| **Lint** | Checks for code that compiles but looks wrong. |
| **Git hook** | A script git runs at a certain moment, such as before a push. |
| **Branch protection** | A GitHub rule that stops direct pushes to a branch and requires reviewed pull requests. |
| **Test tag** | A label on a test (`@Tag("integration")`) used to run groups of tests separately. |
| **CI (continuous integration)** | A server that builds and tests every change automatically. |
| **Workflow** | A GitHub Actions YAML file saying when to run and what steps to do. |
| **Runner** | The machine a CI job runs on. |
| **Concurrency (CI)** | A rule that stops overlapping runs, for example cancelling an older run. |
| **Docker / container** | A way to run a program in an isolated, repeatable environment. |
| **Docker Compose** | A YAML file that starts several containers together. |
| **Image** | The packaged program a container starts from. |
| **Multi-stage build** | A Dockerfile that compiles in one image and runs in a smaller one. |
| **Database role** | A Postgres login with its own permissions. |
| **BYPASSRLS** | A Postgres attribute that skips row-level security. The app role must never have it. |
| **Testcontainers** | A test library that starts a real container, such as Postgres, for the duration of a test. |
| **Integration test** | A test that uses real parts together (the app and a real database), as opposed to a unit test. |
| **Environment variable** | A named setting passed to a program from outside, so secrets stay out of the code. |
| **ArchUnit** | A library for writing tests about code structure, such as which packages may use which. |
| **Architecture test** | A test that fails when the code's structure breaks a design rule. |
| **Vacuous rule** | A rule that passes only because there is nothing for it to check. |
| **Layer** | A level in the dependency direction: api, then core, then ledger. Dependencies point down only. |
| **ADR (decision record)** | A short document of one decision: context, decision, consequences. Kept in `docs/decisions/`. |
| **Record (Java)** | A short way to write an immutable class: the fields, constructor, accessors and `equals` come for free. |
| **Value object** | A small immutable thing defined by its contents, such as `Money`. |
| **Hash (SHA-256)** | A one-way function that turns any data into a 32-byte fingerprint. Any change to the data changes it completely. |
| **Merkle tree / root** | Hashes of hashes, up to one root that fingerprints every entry at once. |
| **Inclusion proof** | A few hashes that prove one entry is in a tree with a given root. |
| **Consistency proof** | A few hashes that prove a later tree extends an earlier one without rewriting it. |
| **Digital signature (ECDSA)** | Proof that the holder of a private key committed to some data. Anyone with the public key can check it. |
| **Hash chain** | Each item contains the hash of the one before, so changing the past breaks everything after it. |
| **Seal** | A signed Merkle root for one council's day, chained to the previous day's seal. |
| **Genesis** | The first link of a chain. For seals it is 32 zero bytes. |
| **Canonical encoding** | One exact, unambiguous way to turn data into bytes so everyone hashes the same thing. |
| **Provenance** | How sure we are of an entry: `VERIFIED`, `DEPOSITED` or `DECLARED`. |
