# M-04: Local stack

Card: `M-04` · Branch: `feature/M-04-compose` · Depends on: M-01

## What we are building, in one paragraph

A real database for the apps to talk to, started with one command. `docker compose up` starts
Postgres plus our three services (`msika-api`, `zamsim`, `elevysim`), each connected to its own
database. The apps create their tables through versioned migrations, and their `/health` now also
says whether the database is up. We also add integration tests that start a throwaway Postgres
and boot the real app against it.

## New ideas in this lesson

### Docker and Docker Compose
- **What it is:** Docker runs programs in isolated *containers* from *images* (a packaged program with
  everything it needs). Docker Compose describes several containers in one YAML file and starts them
  together.
- **Why here:** nobody has to install Postgres on their laptop, and everyone gets the same version
  (`postgres:18.7`, pinned exactly).

### Dockerfile and multi-stage builds
- **What it is:** the recipe for building an image. A *multi-stage* build uses one image to compile
  (full JDK and Gradle) and a second, smaller one to run (JRE only), copying just the finished `.jar`.
- **Why here:** the running image stays small, and `docker compose up --build` works on a machine that
  has Docker and nothing else.

### One Postgres, three databases
- **What it is:** one Postgres server can hold many databases. We create `msika`, `zamsim` and
  `elevysim`, each with its own login.
- **Why here:** `AGENTS.md` rule 6: Msika never reads a simulator's database. Separate databases and
  separate logins make that physically impossible, not just a promise.

### Database roles: owner versus app
- **What it is:** a *role* is a Postgres login with permissions. We make two for Msika:
  - `msika_owner` owns the tables and runs the migrations.
  - `msika_app` is what the running app uses. It can read and write data but owns nothing, cannot
    create or drop tables, and does not have `BYPASSRLS`.
- **Why here:** rule 4. In M-08 we use row-level security so each council only sees its own rows.
  Postgres skips those rules for a table's owner and for any role with `BYPASSRLS`. If the app
  connected as the owner, the security would silently do nothing.

### Flyway and migrations
- **What it is:** Flyway applies numbered SQL files (`V1__baseline.sql`, `V2__...`) in order and
  records which ones ran, in a table called `flyway_schema_history`.
- **Why here:** the database structure is part of the code. Anyone can rebuild an identical database.
  The rule: never edit a migration once it is on `develop`. Add a new one instead.
- **Tiny example:** `V2__create_market.sql` containing `create table market (...)`.

### Testcontainers
- **What it is:** a library that starts a real Docker container from inside a test and throws it away
  afterwards.
- **Why here:** `AGENTS.md` says integration tests use a real Postgres, not H2 or a shared database.
  A fake database hides bugs that only a real one shows, such as permission errors.

### Environment variables with defaults
- **What it is:** `${MSIKA_APP_PASSWORD:dev_only_msika_app}` means "use the variable if set, otherwise
  this default".
- **Why here:** the repository is public (rule 9). The defaults are obviously fake and local only. A
  real server sets real values from the environment. Real secrets never go in git.

## Where it sits in the architecture

```
docker compose
  postgres  ---- database msika     (owner: msika_owner, app: msika_app)
            ---- database zamsim    (user: zamsim)
            ---- database elevysim  (user: elevysim)
  msika-api  :8080  --- uses the msika database
  zamsim     :8081  --- uses the zamsim database
  elevysim   :8082  --- uses the elevysim database
```

Msika and the simulators share a Postgres *server* but nothing else. They only ever talk over HTTP
(from M-12 on).

## Step by step

1. Branched from M-03 and marked the card ◐. Started Docker Desktop (its engine was not running).
2. Looked up exact versions: Postgres 18.7, Temurin 21.0.13. Spring Boot's BOM supplies the Postgres
   driver, Flyway and Testcontainers versions.
3. **`infra/postgres/init/01-init.sh`.** Runs once on a fresh Postgres. Creates the four roles and the
   three databases, and grants `msika_app` only what it needs. The schema's default privileges mean
   any table `msika_owner` creates later is automatically readable and writable by `msika_app`.
4. **`infra/docker-compose.yml`, `infra/Dockerfile`, `.dockerignore`, `infra/.env.example`.**
5. **Dependencies and config per app:** JDBC, Flyway, the Postgres driver. For `msika-api`, Flyway
   connects as `msika_owner` and the datasource as `msika_app`.
6. **`V1__baseline.sql`** in each app: an empty first migration, so version 1 exists.
7. **Health shows the database:** `management.endpoint.health.show-components=always`.
8. **Integration tests** (tagged `integration`, so they run in the full check, not the fast one):
   - Each app: start Postgres with the same init script as compose, boot the app, call `/health`, expect
     `UP` and `"db":{"status":"UP"}`.
   - `msika-api` also proves the role rules (see below).
9. **Ran the whole stack** with `docker compose up --build` and fetched the three health endpoints.

## Files touched

| File | What changed | Why |
|---|---|---|
| `infra/docker-compose.yml` | New | Postgres and the three services |
| `infra/postgres/init/01-init.sh` | New | Databases and roles, shared by compose and the tests |
| `infra/Dockerfile`, `.dockerignore` | New | Build and run one module |
| `infra/.env.example` | New | The variables a real environment overrides |
| `platform/*/build.gradle.kts` | DB, Flyway, Testcontainers dependencies | Database support |
| `platform/*/src/main/resources/application.properties` | Datasource, Flyway, health components | Connect to the right database |
| `platform/*/src/main/resources/db/migration/V1__baseline.sql` | New | First migration |
| `platform/*/src/test/.../HealthEndpointTest.java` | Now an integration test with Postgres | Proves the app boots against a real database |
| `platform/msika-api/src/test/.../DatabaseRolesTest.java` | New | Proves the role rules |

## How we know it works

| Done item | Proof |
|---|---|
| `docker compose up` starts everything and health endpoints report the database as up | Ran it, then fetched all three `/health` endpoints (result in the pull request). |
| An integration test boots `msika-api` against Testcontainers Postgres | `HealthEndpointTest` and `DatabaseRolesTest` in `msika-api`. |
| The app role has no table ownership and no `BYPASSRLS` | `DatabaseRolesTest`: not a superuser, no `BYPASSRLS`, owns no tables, and `create table` fails with `permission denied for schema public`. Migrations show `installed_by = msika_owner`. |

## Rebuild it yourself

1. Write a `docker-compose.yml` with only Postgres and start it. Connect with `psql` or a database
   tool.
2. Add an init script that creates a database and two roles. Prove the second role cannot create a
   table.
3. Add Flyway to a Spring Boot app with one migration that creates a table. Look at
   `flyway_schema_history`.
4. Write an integration test with Testcontainers that boots the app.
5. Containerise the app with a two-stage Dockerfile and add it to compose.

## Mistakes to avoid

- **Connecting the app as the table owner.** Row-level security (M-08) would not apply to it.
- **Editing a migration that is already shared.** Flyway checks a checksum and will refuse to start.
  Write a new migration.
- **Asserting on a wrapped exception message.** Our first role test failed because Spring wraps the
  database's `permission denied` error. Look at the root cause.
- **Real passwords in compose files.** Only obvious local defaults belong in git.
- **Forgetting Docker must be running.** The integration tests need it. The fast check does not.
