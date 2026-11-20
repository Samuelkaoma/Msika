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
| **API / REST** | A set of URLs a program can call, designed around things (councils, stalls) and the HTTP verbs. |
| **HTTP verbs** | `GET` read, `POST` create, `PUT` set to this state, `DELETE` remove. |
| **Status code** | A number saying how a request went: 200 ok, 201 created, 400 bad request, 404 not found, 409 conflict. |
| **Controller** | The class that receives HTTP requests and returns responses. It holds no business rules. |
| **DTO** | A small object shaped for the wire (request or response), separate from the domain object. |
| **Repository** | An interface for saving and finding things. Our adapters implement it with SQL. |
| **Use case** | One thing the system can do, as a class: `AllocateStall`, `ImportStalls`. |
| **Transaction** | Database changes that all happen or none do. |
| **Primary key / foreign key / unique** | Constraints the database enforces: identify a row; must point at a real row; no duplicates. |
| **Composite foreign key** | A foreign key over two columns, used here so a row cannot point across councils. |
| **Partial unique index** | A uniqueness rule that applies only to some rows, for example only active allocations. |
| **Fake (test)** | A simple in-memory stand-in for a real dependency, used to test rules without a database. |
| **JSON** | The text format APIs use to send data, such as `{"code": "SOW"}`. |
| **Multi-tenancy** | One system serving several customers (here, councils) whose data must stay apart. |
| **RLS policy** | A rule attached to a table that Postgres applies to every query on it. |
| **`using` / `with check`** | The two halves of a policy: which rows you can see, and which rows you may write. |
| **Session setting (`set_config`)** | A named value attached to a database connection or, with `true`, to one transaction. |
| **Fail closed** | When something is missing, deny (return nothing) rather than allow. |
| **Connection pool** | A small set of reused database connections, which is why a tenant setting must be local to a transaction. |
| **Authentication** | Proving who you are: email and password, then a token. |
| **Authorization** | Deciding what you may do: your role, and which council. |
| **401 / 403** | 401: we do not know who you are. 403: we know, and the answer is no. |
| **Hash / salt** | A one-way scramble of a password, with a random addition so equal passwords hash differently. |
| **BCrypt** | A password hash that is deliberately slow, so guessing is expensive. |
| **JWT** | A small signed token naming a user, sent as `Authorization: Bearer ...`. Readable by anyone, changeable by no one. |
| **Stateless** | The server keeps no session for you; the token carries what it needs. |
| **Role** | A named set of permissions: operator, council admin, council officer. |
| **Default deny** | Everything is refused unless a rule allows it. |
| **CSRF** | An attack that rides on a browser's automatic cookies. Not a risk with bearer tokens in a header. |
| **`SECURITY DEFINER`** | A Postgres function that runs with its owner's rights. We use two tiny ones for sign-in lookups. |
| **Audit log** | An append-only record of who changed what and when. |
| **Bootstrap operator** | The first operator, created from environment settings at start-up. |
