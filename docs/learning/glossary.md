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
