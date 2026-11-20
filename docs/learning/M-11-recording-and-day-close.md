# M-11: Recording entries and day close

Card: `M-11` · Branch: `feature/M-11-day-close` · Depends on: M-06, M-08

## What we are building, in one paragraph

The ledger becomes real. There is now exactly one way to put an entry on it (`RecordEntry`), repeating a
request is harmless, the database itself refuses to let history change, and every evening each council's
day is **sealed**: fingerprinted, signed and chained to the day before. A separate tool, `scripts/verify-seal`,
rebuilds those fingerprints from the raw rows and says whether anyone altered anything, even someone who
got round the application and the database.

## The big idea: three layers, one rule

The rule is "the ledger only grows" (AGENTS.md rule 2). Rules that rely on one mechanism are fragile, so
we enforce it three ways, each catching what the one before cannot:

| Layer | What it is | What it stops |
|---|---|---|
| 1. One door | Only the `RecordEntry` use case writes the ledger | A programmer's mistake |
| 2. The database | No `UPDATE`/`DELETE`/`TRUNCATE` privilege; triggers that refuse them (even for the owner); a trigger that refuses an entry in a sealed day; `check` constraints that mirror the domain rules | A bug, or someone with database access running a careless query |
| 3. The seal | Each day's entries are hashed into a Merkle tree, signed, and chained to the previous day, and `verify-seal` checks it | A determined person who switches the triggers off and edits history |

Layer 3 does not *prevent* tampering. It makes tampering **detectable**, which is the most any system
can promise against someone who controls the database.

## New ideas in this lesson

### Idempotency, properly (AGENTS.md rule 5)
- **What it is:** doing the same thing twice has the same effect as doing it once. Networks retry, SMS
  gateways resend, people tap twice. If a retry recorded money twice, the ledger would be wrong.
- **How:** every entry carries an *idempotency key* and the database has `unique (council_id, idempotency_key)`.
  Recording the same key and the **same content** returns the existing entry ("duplicate"). The same key
  with **different content** is a conflict (`409`), never a silent overwrite, because that means two
  different things were given the same name.
- **Comparing content:** an entry's time has nanoseconds in Java but only microseconds in Postgres. We round
  it down when the request is built, so what we compare and what we seal is exactly what is stored.

### The business date, and why it is Lusaka's
- A trading day belongs to the council's time zone. `22:00 UTC` is midnight in Lusaka, so it is already
  the **next** day there. A test checks the boundary to the second.
- **A sealed day is closed.** An entry whose day is already sealed (or earlier) goes to the **next open
  day**; the real time it happened (`occurred_at`) is kept. If a council has no seals yet, the first open
  day is the day it was created. This single rule means a sealed day never changes, and every entry always
  lands on a day that will be sealed.

### Triggers and privileges
- **Privilege:** `revoke update, delete, truncate on ledger_entry from msika_app`. The application's role
  simply cannot. The error is `permission denied`.
- **Trigger:** a function Postgres runs automatically before a row changes. Ours raises an error, so even
  the table's owner cannot update or delete. `ledger_entry is append-only: UPDATE is not allowed`.
- **Check constraints:** `check (case when kind = 'REVERSAL' then amount < 0 else amount > 0 end)` and
  friends repeat the domain's rules in SQL, so even a bug cannot store a declared sale as verified.
- A **superuser or the owner can still switch a trigger off.** We do not pretend otherwise. That is exactly
  what layer 3 is for.

### Locks, so recording and closing never collide
- A **Postgres advisory lock** is a named lock the application takes and the database enforces. Ours lasts
  until the transaction ends.
- Recording takes it **shared** (many entries can be recorded at once). Closing a day takes it
  **exclusive**: it waits for every in-flight recording to finish, and while it runs no new one starts.
  When it commits, later entries see the new seal and go to the next day. Different councils use
  different locks, so they never wait for each other.

### The seal, and the chain (from M-06, now used)
`DailySeal.seal` builds the Merkle tree over the day's entries **in id order**, and signs
`(council, date, entry count, root, previous seal's hash)`. Each seal contains the hash of the one before,
starting from 32 zero bytes. Closing a day also closes any earlier day that was missed, oldest first, so
the chain never has a gap, and a day with no entries is sealed too.

### Signing keys (ARCHITECTURE.md section 9)
The private key that signs seals must never be in the repository. It comes from the environment on a real
server; on a laptop a development key is generated on first run into `.msika-keys/`, which git ignores.
The **public** key is published at `GET /api/v1/seal-key`, because a key only we could read would prove
nothing. One test checks a seal's signature using only the published key and the response.

### The verifier, and exit codes that mean something
`scripts/verify-seal` reads the raw rows, rebuilds every day, and checks signature, chain, coverage and
gaps. It exits **0** (fine), **1** (altered) or **2** (could not run). The difference between 1 and 2
matters: a script must be able to tell tampering from a mistyped password. It needs the **owner** role,
because row-level security hides other councils from the app role.

## Two bugs, and how we found them

**1. A race that passed by luck.** Our first concurrency test (eight threads recording one key) passed.
In the full check it failed once: `current transaction is aborted`. When two requests insert the same key,
Postgres aborts the *loser's whole transaction* on the duplicate-key error, so our recovery code (re-read
the winner) ran inside an aborted transaction. A single passing run meant nothing, because a race is about
timing. We made the test run **twenty rounds** so it failed every time, fixed it with
`INSERT ... ON CONFLICT DO NOTHING` (a duplicate becomes "no row inserted", not an error), and then ran it
several more times to be sure. *Lesson: a concurrency test that passes once proves little. Make it
reproduce the bug before you fix it.*

**2. A script that hid the answer.** We wrapped the verifier in a Gradle task. Trying the script for real
showed Gradle turns every non-zero exit into `1`, so "altered" and "could not run" looked identical. We
changed the script to ask Gradle only for the Java command and classpath, then run Java directly. *Lesson:
run the thing the way a user will, and check the exit code, not just the text.*

## Proving it works, three ways

1. **Unit tests** with in-memory fakes (core): idempotency, the time-zone boundary, each reversal rule,
   catch-up closing, the chain.
2. **Integration tests** against real Postgres: eight threads on one key, four closes at once, four threads
   recording while another closes (nothing lost, nothing in a sealed day, every seal verifies), the
   database refusing every kind of change.
3. **Attacker tests.** Each switches off a trigger as the owner and edits history: change an amount, delete
   an entry, slip one in, move one to another day, **promote a declared sale to verified**, edit a seal,
   delete a seal, and even an attacker who **recomputes and re-signs the seal with their own key**. The
   verifier catches all of them. We crippled the verifier on purpose and nine of these tests failed.

And once for real: with the actual app, a throwaway Postgres and the actual script, an ordinary edit was
refused (`UPDATE is not allowed`); then we switched the trigger off, changed one entry by a single ngwee,
switched it back on, and `verify-seal` exited **1** naming the council and the day.

## Where it sits in the architecture

```
msika-ledger   Money, LedgerEntry, DailySeal, Merkle tree, signing        (M-06, unchanged)
msika-core     RecordEntry, DayClose, NewEntry, ports: LedgerRepository, SealRepository, LedgerLock
msika-api      Jdbc repositories, PostgresLedgerLock, SealKeys, LedgerService, DayCloseJob,
               SealController, SealVerifier, VerifySealsCli
database       V6: ledger_entry, daily_seal, triggers, privileges, checks
```

## Step by step

1. Branched from M-10, marked the card ◐.
2. **Migration V6** with the tables, constraints, triggers and privilege changes.
3. **Core:** `NewEntry`, the three ports, `RecordEntry` (idempotency, business date, reversal rules),
   `DayClose` (catch-up, safe to repeat). 27 new unit tests.
4. **API:** JDBC adapters, the advisory lock, signing-key handling, `LedgerService`, the scheduled job,
   the seal endpoints and the operator's manual close, security rules.
5. **Verifier and CLI**, then `scripts/verify-seal` for both shells.
6. **Tests:** recording and immutability (14), day close (6), tamper detection and CLI (16), seal API (8).
7. **Found and fixed** the race and the exit-code problem above.
8. **End-to-end run** with the real stack, then cleaned up.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-api/.../db/migration/V6__ledger_and_seals.sql` | New | Tables, triggers, privileges, checks |
| `platform/msika-core/.../ledger/*` | New | `RecordEntry`, `DayClose`, ports |
| `platform/msika-api/.../ledger/*`, `persistence/Jdbc{Ledger,Seal}Repository`, `PostgresLedgerLock` | New | Adapters, service, job, endpoints |
| `platform/msika-api/.../seal/*` | New | Keys, verifier, CLI |
| `scripts/verify-seal.sh`, `.ps1` | New | The tool |
| `platform/msika-api/.../security/SecurityConfiguration.java` | Three rules | Seal endpoints and manual close |
| `infra/docker-compose.yml`, `infra/.env.example`, `README.md`, `.gitignore` | Settings and docs | Keys stay out of git |
| tests | `RecordingAndImmutabilityTest`, `DayCloseTest`, `SealVerifierTest`, `SealApiTest`, core ledger tests | Proof |

## How we know it works

| Done item | Proof |
|---|---|
| Recording the same idempotency key twice gives one entry | `sameKeyTwiceIsOneEntry`; `concurrentSameKey` (20 rounds of 8 threads) |
| An `UPDATE` on the ledger fails at the database | `appRoleCannotChangeTheLedger` (`permission denied`), `ownerCannotChangeTheLedgerEither` (the trigger), and the real run |
| Day close is safe to run twice | `safeToRunTwice` (no seal byte changes), `concurrentCloses`, `scheduledJob` |
| The verify tool fails on a hand-edited database | `SealVerifierTest` (12 tamper scenarios, 3 CLI exit codes) and the real end-to-end run |

## Rebuild it yourself

1. Make a table with a unique key and `INSERT ... ON CONFLICT DO NOTHING RETURNING id`. Run it from two
   sessions at once.
2. Add a `BEFORE UPDATE OR DELETE` trigger that raises an error. Try it as the owner.
3. Hash a list of rows into a Merkle root and sign it. Change a row and verify.
4. Make an advisory lock (`pg_advisory_xact_lock`) and try to take it from two sessions.
5. Write a shell script that exits with different codes and check `$?`.

## Mistakes to avoid

- **Trusting a concurrency test that passed once.** Reproduce the race first.
- **Recovering from a database error inside the same transaction.** After an error, Postgres ignores
  everything until rollback. Avoid the error (`ON CONFLICT`) or use a savepoint.
- **Letting a wrapper change your exit code.** Test the script the way a person will run it.
- **Treating "the trigger exists" as "the ledger is safe".** An owner can switch it off. That is what the
  seal and the verifier are for.
- **Putting a signing key in git.** Environment variable, or a git-ignored file.
- **Pointing a tool at "localhost:5432" without checking what is there.** Another Postgres can be
  listening. We found a native one on this machine while testing and used a different port.

## Not done here (on purpose)

- **One signing key for all councils.** ARCHITECTURE.md says "the council's key"; that arrives with key
  management (M-44/M-45). Each seal records which key signed it (`key_id`), so keys can be added later.
- **No HTTP endpoint records an entry.** Deposits, payments and SMS call `LedgerService.record` in later
  cards (M-14, M-15, M-19).
- **`trader_id` and a `source_ref` are not on the entry.** Adding either changes the bytes that are sealed,
  so it must be decided before real data exists (see the PR).
