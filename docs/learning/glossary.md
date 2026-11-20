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
| **E.164** | The international phone number format: `+` country code and number, no spaces. Ours is `+260` then nine digits. |
| **Normalisation** | Turning many spellings of one value into one canonical form, once, at the edge. |
| **Masking** | Showing only part of a sensitive value, like `0977***739`, so it is safe in logs. |
| **Data minimisation** | Keeping only the personal data you need. |
| **Check constraint** | A database rule on a column, such as "must match this pattern". |
| **PDF / PDFBox** | The file format for printable pages / the Java library we use to make them. |
| **QR code / module** | A grid of black and white squares that encodes text / one square of that grid. |
| **Error correction (QR)** | Extra data in a QR code so it still reads when dirty or torn. We use level Q, about 25%. |
| **Plate** | The A6 printed page for a stall: code, market name and QR code. |
| **ZXing** | The Java library that makes and reads QR codes. |
| **Idempotency key** | A name for one request, so repeating it records one entry. Same key and content: a repeat. Same key, different content: a conflict. |
| **Business date** | The trading day an entry belongs to, in the council's time zone. A sealed day never changes. |
| **Trigger (database)** | A function Postgres runs automatically when a row changes. Ours refuses `UPDATE` and `DELETE` on the ledger. |
| **Advisory lock** | A named lock the application asks Postgres to hold. Shared for recording, exclusive for closing a day. |
| **`ON CONFLICT DO NOTHING`** | An insert that quietly skips a duplicate instead of raising an error that aborts the transaction. |
| **Day close** | Sealing a finished trading day: Merkle root over its entries, signed and chained to the day before. |
| **Verifier** | The independent tool (`scripts/verify-seal`) that rebuilds every seal from raw rows and reports any alteration. |
| **Exit code** | The number a program returns to say how it went: 0 fine, non-zero a problem. Ours: 1 altered, 2 could not run. |
| **Tamper-evident** | Changes cannot be prevented absolutely, but they are always detectable. |
| **Simulator (zamsim)** | Our stand-in for Zamtel Money. It follows the real rules so Msika learns the real behaviour. |
| **Double-entry** | Every money movement is lines that add up to zero: one wallet loses what another gains. |
| **Issuer wallet** | The special wallet that holds minus all e-money, so the whole system always sums to zero. |
| **Fund / withdraw** | Money entering the system from the issuer, and leaving it back to the issuer. |
| **`select ... for update`** | Locks rows until the transaction ends, so two payments cannot spend the same money. |
| **Deadlock** | Two transactions each waiting for a lock the other holds. Prevented by always locking in the same order. |
| **Deferred constraint trigger** | A database check that runs when the transaction commits, after all its rows exist. |
| **Running balance** | The wallet's balance after each ledger line, shown on a statement. |
| **Conservation** | Money is neither created nor destroyed by a transfer: totals before and after are equal. |
| **Callback / webhook** | A call the payments system makes to our URL when something happens, instead of us asking. |
| **HMAC** | A fingerprint of a message made with a shared secret. Different message or secret, different fingerprint. |
| **Replay attack** | Re-sending a genuine recorded message later. Stopped by signing a timestamp and rejecting old ones. |
| **Outbox** | A table of messages to send, written in the same transaction as the event they describe. |
| **At-least-once delivery** | A message may arrive more than once but is never lost. Receivers must be idempotent. |
| **Exponential backoff** | Waiting longer after each failed attempt, so a struggling receiver is not hammered. |
| **Dead letter (DEAD)** | A message that has been given up on after its last attempt, kept with the reason. |
| **`FOR UPDATE SKIP LOCKED`** | Lets several workers take different rows from one queue without waiting. |
| **Fault injection** | Making a system misbehave on purpose (fail, delay, duplicate, reorder) to prove callers cope. |
| **Request-to-pay** | A merchant asks a customer to pay. It waits until the customer approves, rejects, or it expires. |
| **Port / adapter** | The port is an interface of what we need; the adapter is the class that does it with a particular outside system. |
| **Payment reference** | A value we choose (`msika:<council>:<request>`) that the payments system echoes back, so a callback finds its request. |
| **Fail closed** | When something needed to check is missing (no secret), refuse everything rather than allow everything. |
| **Idempotency key** | A value sent with a request so repeating it does the thing once. |
| **State machine** | A thing that can only move along a fixed set of allowed status changes. |
| **Atomic status change** | `update ... where status = old`: only one of two competing changes finds the old status. |
| **Wallet registry** | Msika's list of which payments-system wallet belongs to which stall, agent or supplier. |
| **`SECURITY DEFINER` function** | A database function that runs with its owner's rights, used for one narrow lookup across the security wall. |
| **Poison message** | A message that fails every time. Answering "try again" makes the sender retry it forever; answer 400 instead. |
| **Write, then check** | Insert, then check, in one transaction, so a failed check rolls the insert back and nothing is stored. |
| **Reconciliation** | Comparing two independent records of the same money (Msika's ledger and the payments system's statements) and listing every difference. |
| **Statement** | The payments system's list of settled movements on one wallet between two dates. |
| **Parser** | Code that reads free text (an SMS body) and produces a typed command, or says why it cannot. |
| **Sealed interface** | A Java interface that lists every class allowed to implement it, so a `switch` must handle each one. |
