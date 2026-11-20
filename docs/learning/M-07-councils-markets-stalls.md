# M-07: Councils, markets and stalls

Card: `M-07` · Branch: `feature/M-07-stalls` · Depends on: M-04, M-05

## What we are building, in one paragraph

The first real feature. A council exists, has markets, and each market has stalls with codes like
`SOW-B-114`. An admin can upload a CSV file to create hundreds of stalls at once, allocate a stall to
a trader, and free it again. This is also the first time we write a REST API, store data in the
database, and use the ports-and-adapters design from the architecture, so this lesson explains all
three from scratch.

## New ideas in this lesson

### What an API is, and what REST means
- **What it is:** an API is a set of URLs another program can call. Your phone app, the council
  dashboard and our tests all talk to the server by sending an HTTP request and reading the answer.
  *REST* is a convention for designing those URLs around *things* (councils, markets, stalls) using the
  HTTP verbs.
- **The verbs we use:**

  | Verb | Meaning | Example here |
  |---|---|---|
  | `GET` | read something, change nothing | list a market's stalls |
  | `POST` | create something new | create a market |
  | `PUT` | set something to this state; repeating it is harmless | allocate a stall to a trader |
  | `DELETE` | remove or end something | free a stall |

- **Status codes** say how it went: `200` ok, `201` created, `204` ok with nothing to say, `400` your
  request is wrong, `404` that does not exist, `409` that clashes with the current state.
- **Why `/api/v1/`:** every path starts with it (AGENTS.md). The `v1` lets us change the API later
  without breaking old callers.
- **Tiny example** (this is the whole idea of a controller):
  ```java
  @GetMapping("/api/v1/councils/{councilId}/markets")
  List<MarketResponse> listMarkets(@PathVariable UUID councilId) { ... }
  ```
  `@GetMapping` says "answer GET requests to this URL", `{councilId}` is a piece of the URL that
  arrives as a parameter, and whatever you return is turned into JSON.

### Controller, service, use case, repository: who does what
```
HTTP request
   |
CouncilAdminController   (msika-api)   reads the request, shapes the response. No rules.
   |
CouncilAdminService      (msika-api)   opens a database transaction around one use case.
   |
AllocateStall etc.       (msika-core)  the rules. Plain Java. No Spring, no SQL.
   |
StallRepository          (msika-core)  an interface: "something that can save and find stalls".
   |
JdbcStallRepository      (msika-api)   the real thing: SQL against Postgres.
```
- **Port and adapter, for real this time:** `StallRepository` (in `msika-core`) is a *port*, a promise
  of what we need. `JdbcStallRepository` (in `msika-api`) is an *adapter* that keeps the promise with
  SQL. The rules in `msika-core` never see SQL, so we can test them with simple in-memory fakes
  (`Fakes.java`) in milliseconds.
- **DTOs:** the small `record` types inside the controller (`MarketResponse`, ...) are what goes over
  the wire. We never send our domain objects directly, so we can change one without breaking the other.

### Database migration (Flyway)
- `V2__councils_markets_stalls.sql` creates five tables. It never changes once merged. A later change
  is a new file, `V3__...`.
- **Primary key, foreign key, unique:** a primary key identifies a row; a foreign key says "this must
  point at a real row in that other table"; a unique constraint says "no two rows may share this".
  The database enforces all three, whatever the application code does.
- **Composite foreign keys for tenancy:** `stall` points at `market` using *both* `market_id` and
  `council_id`. So the database itself refuses a stall in council A whose market belongs to council B.
  We test that by going around the application and inserting directly.
- **Partial unique index:** `unique (stall_id) where valid_to is null` means "at most one allocation
  per stall that has not ended". Even two simultaneous requests cannot both win.

### Transactions
- **What it is:** a group of database changes that either all happen or none do.
- **Why here:** importing 300 stalls must not leave 150 behind if row 151 fails. The service wraps each
  use case in `@Transactional`, so an exception undoes everything.

### Reading a CSV safely (`StallCsv`)
- Pure Java, so we can test it with no server. The whole file is accepted or rejected. A bad row is
  reported with its **row number** as the person sees it in their spreadsheet (header is row 1). We
  report every bad row at once, up to 50, so they can fix the file in one go.
- Row numbers, blank lines, Windows line endings, a byte-order mark and quoted fields are all tests.

### Idempotency (AGENTS.md rule 5) without a special header
Making a request twice should have the same effect as making it once, because networks retry.
- `PUT .../allocation` for the same trader twice returns the same allocation.
- `DELETE .../allocation` on a free stall is a harmless `204`.
- Creating a market or stall whose code exists gives `409` and creates no duplicate. The database's
  unique constraints are the final guard when two requests race.
The generic `Idempotency-Key` header machinery comes with the cards that need it (payments, ledger).

### Error handling in one place
`ApiExceptionHandler` turns three plain exceptions from `msika-core` (`InvalidInputException`,
`NotFoundException`, `ConflictException`) into `400`, `404` and `409` with one JSON shape. The core
never mentions HTTP.

### Making invalid states impossible (again)
`Market`, `Stall` and `Council` validate in their constructors, so an invalid one cannot exist. A
market code is 2 to 5 capital letters; a stall's QR token is a random 128-bit secret, never derived
from the stall code, so you cannot guess one stall's token from another.

## Where it sits in the architecture

```
msika-api   admin/ (controller, service, config, error handler)   persistence/ (Jdbc* adapters)
msika-core  council/ market/ stall/ common/   (use cases, domain, ports; plain Java)
msika-ledger  (not used yet)
```
The M-05 architecture tests run over this new code. They pass, so `msika-core` still imports no Spring,
JPA, Jackson or HTTP client.

## Step by step

1. Branched from M-06, marked the card ◐.
2. **Domain in `msika-core`:** `Council`, `Market`, `Stall`, `StallStatus`, `StallAllocation`, the
   repository interfaces, `TraderDirectory`, and the use cases `CreateCouncil`, `CreateMarket`,
   `ImportStalls`, `AllocateStall`, `FreeStall`. Plus the three exceptions and `Tokens`.
3. **`StallCsv`** parser.
4. The strict lint (from M-02) immediately caught a real problem: exceptions need a
   `serialVersionUID`. We fixed it rather than loosening the lint.
5. **Unit tests** with in-memory fakes: 33 tests.
6. **Migration `V2`** with the tables and constraints.
7. **JDBC adapters** for each repository, mapping database errors to `ConflictException`.
8. **Service, wiring and controller:** `CouncilAdminService` (transactions), `CoreConfiguration`
   (hands repositories to the plain use-case classes), `CouncilAdminController`,
   `ApiExceptionHandler`.
9. **Integration tests** through the real HTTP API and a real Postgres. A shared base class
   (`PostgresIntegrationTest`) starts one Postgres for all of them, so they run faster. We moved the
   earlier health and role tests onto it.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-core/.../council, market, stall, common` | New | Domain, use cases, ports |
| `platform/msika-core/src/test/...` | New: `Fakes`, `StallCsvTest`, `StallUseCasesTest` | Unit tests |
| `platform/msika-api/.../persistence/Jdbc*.java` | New | Adapters |
| `platform/msika-api/.../admin/*.java` | New | Controller, service, wiring, errors |
| `platform/msika-api/src/main/resources/db/migration/V2__...sql` | New | The tables |
| `platform/msika-api/src/test/.../PostgresIntegrationTest.java` | New | Shared Postgres for tests |
| `platform/msika-api/src/test/.../admin/CouncilAdminApiTest.java` | New | API tests |
| `.../HealthEndpointTest.java`, `DatabaseRolesTest.java` | Now use the shared base | Faster, no duplication |

## How we know it works

| Done item | Proof |
|---|---|
| Create | `CouncilAdminApiTest`: council, market, duplicate code `409`, unknown council `404`, bad code `400` |
| Bulk import | `bulkImport`: 3 stalls, codes `SOW-B-114`..., vacant, unique 22-character tokens |
| A CSV with a bad row is rejected with the row number | `bulkImportWithBadRow`: `400`, `rows[0].row == 3`, nothing created. Also `StallCsvTest` (every bad-row case) |
| Allocate and free a stall | `allocateAndFree`: allocate, repeat safely, second trader `409`, free, free again, history kept, re-allocate |
| Councils are separate | `councilsAreSeparate`, plus two tests that go straight at the database and prove its constraints |

## Rebuild it yourself

1. Write a `Market` record whose constructor rejects a bad code. Test it.
2. Define a `MarketRepository` interface and an in-memory fake. Write a `CreateMarket` use case and test
   it with the fake, no Spring involved.
3. Write a Flyway migration for a `market` table with a unique constraint.
4. Write a `JdbcMarketRepository` with Spring's `JdbcClient`.
5. Write a `@RestController` with `POST` and `GET`. Call it with `curl`.
6. Add a `@RestControllerAdvice` that turns a `ConflictException` into `409`.
7. Write an integration test that starts Postgres with Testcontainers and calls your API.

## Mistakes to avoid

- **Putting rules in the controller.** They belong in `msika-core`, where they are easy to test.
- **Sending domain objects as JSON.** Use separate response records.
- **Trusting the application to keep data consistent.** Put the rule in the database too (unique
  indexes, composite foreign keys). Then a bug or a race cannot corrupt it.
- **Forgetting a transaction around a multi-row write.** A half-finished import is worse than a failed
  one.
- **Assuming a test starts with an empty database.** Our tests share one Postgres, so each makes its
  own council.
- **Exposing these endpoints.** They have no sign-in yet (M-09) and the database does not yet enforce
  tenancy (M-08).
