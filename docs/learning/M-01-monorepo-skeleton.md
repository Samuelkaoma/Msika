# M-01: Monorepo skeleton

Card: `M-01` · Branch: `feature/M-01-skeleton` · Depends on: none

## What we are building, in one paragraph

An empty but working system. Five Java modules and two React apps exist, build with one command each,
and three of the Java apps answer a `/health` request. No business logic yet. The point is that
everyone can clone the repo, run `./gradlew build` and `pnpm -r build`, and get green. Every later
card adds to this skeleton instead of arguing about how the project is laid out.

## New ideas in this lesson

### Monorepo
- **What it is:** one git repository holding several projects (Java services and web apps) instead of
  one repository each.
- **Why here:** the ledger, the API, the simulators and the dashboard change together. One pull
  request can touch all of them, and one check run covers them all.

### Gradle, multi-module builds, and the wrapper
- **What it is:** Gradle is the build tool for Java. It compiles code, runs tests and packages the
  app. A *multi-module* build is one Gradle project made of several sub-projects (modules) that can
  depend on each other. The *wrapper* (`gradlew`) is a small script, committed to the repo, that
  downloads the exact Gradle version the project needs.
- **Why here:** nobody has to install Gradle or worry about versions. We also made the modules
  separate so that the rule "`msika-core` imports no Spring" can be enforced by the build: the module
  simply does not have Spring on its classpath.
- **Tiny example:** `settings.gradle.kts` lists the modules; each module's `build.gradle.kts` lists
  what it needs.

### Spring Boot
- **What it is:** a framework that starts a web server inside your Java program and wires parts
  together for you. You write a class with `@SpringBootApplication` and a `main` method, and you
  have a running web app.
- **Why here:** `msika-api`, `zamsim` and `elevysim` are all web services.

### BOM (bill of materials)
- **What it is:** a list that says "these versions of these libraries work together".
- **Why here:** we write `spring-boot-starter-webmvc` with no version, and the Spring Boot BOM picks
  the right one. We pin the Boot version once (`4.2.0`) and everything else follows.

### Health endpoint and Actuator
- **What it is:** a URL that answers "are you alive?". Spring Boot Actuator provides one ready made.
- **Why here:** later cards extend it to check the database (M-04), and deployments use it to know
  an app has started. We set the base path to `/` so it is `/health`, as the card says, and expose
  only `health` so nothing else leaks.

### pnpm workspace, Vite, React, TypeScript
- **pnpm workspace:** the JavaScript version of a multi-module build. `pnpm-workspace.yaml` says
  "every folder under `apps/` is a package", and `pnpm -r build` runs each one's `build` script.
- **Vite:** a fast build tool and dev server for web apps.
- **React:** a library for building screens out of small components (functions that return markup).
- **TypeScript:** JavaScript with types. `tsc --noEmit` only checks types; `vite build` makes the
  files that ship.

### Pinning versions
- **What it is:** writing `4.2.0`, not `^4.2.0` or `4.+`, so the same code builds the same way next
  month.
- **Why here:** a card rule, and a good one: an update you did not choose cannot break a build.

## Where it sits in the architecture

```
msika-api ──► msika-core ──► msika-ledger        (plain Java at the bottom)
zamsim, elevysim                                  (separate apps, depend on nothing of ours)
apps/council-web, apps/sim-console                (React, talk to the API over HTTP later)
```

This matches `docs/ARCHITECTURE.md` §3. It protects rule 7 (core and ledger import no Spring) by
giving those two modules no Spring dependency, and rule 6 (Msika never imports `zamsim` or
`elevysim`) because `msika-api` has no dependency on them in its build file. The ArchUnit test that
enforces both properly comes in M-05.

## Step by step

1. **Branch.** `git switch develop`, then `git switch -c feature/M-01-skeleton`. Marked the card
   ◐ in `docs/ROADMAP.md`.
2. **Look up real versions** instead of guessing: Gradle 9.9.2, Spring Boot 4.2.0, JUnit 6.1.3, and
   the npm packages (`npm view <name> version`).
3. **Write `settings.gradle.kts`.** Lists the five modules and points each at `platform/<name>`.
4. **Write the root `build.gradle.kts`.** For every module: Java 21, Maven Central, JUnit.
5. **Write one `build.gradle.kts` per module.**
   - `msika-ledger`, `msika-core`: `java-library`, test dependencies only. `msika-core` depends on
     `msika-ledger`.
   - `msika-api`, `zamsim`, `elevysim`: the Spring Boot plugin, web and actuator starters.
     `msika-api` depends on `msika-core`.
6. **Generate the wrapper.** Gradle was not installed, so we downloaded the distribution to a
   temporary folder, checked its SHA-256 against Gradle's published value, and ran
   `gradle wrapper`. The checksum is now recorded in `gradle/wrapper/gradle-wrapper.properties`, so
   every machine verifies its download.
7. **Write the three apps**: a class with `@SpringBootApplication` and `main`, and an
   `application.properties` giving the port (8080, 8081, 8082) and the `/health` setting.
8. **Write a health test per app** (see below) and run `./gradlew build`.
9. **Write the pnpm workspace**: root `package.json`, `pnpm-workspace.yaml`, `.npmrc` with
   `save-exact=true`, then the two apps, each with `package.json`, `index.html`, `vite.config.ts`,
   `tsconfig.json`, `src/main.tsx` and `src/App.tsx`.
10. **Repo hygiene**: `.gitignore` (build output, secrets), `.editorconfig`, `.gitattributes` (LF line
    endings), and the root `README.md` quick start.
11. **Run one app for real** and fetch `/health` with `curl`, then stop it.

## Files touched

| File | What changed | Why |
|---|---|---|
| `settings.gradle.kts` | New. Lists the five modules | Defines the multi-module build |
| `build.gradle.kts` | New. Shared Java 21 and test setup | Avoids repeating it in every module |
| `platform/*/build.gradle.kts` | New, one per module | Each module's own dependencies and its place in the dependency direction |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | New, generated | Nobody needs Gradle installed |
| `platform/{msika-api,zamsim,elevysim}/.../*Application.java` | New | The entry point of each app |
| `platform/*/src/main/resources/application.properties` | New | Port and `/health` path |
| `platform/*/src/test/.../HealthEndpointTest.java` | New | Proves `/health` answers `UP` |
| `platform/{msika-ledger,msika-core}/.../package-info.java` | New | Gives the empty modules something to compile and says what they are for |
| `package.json`, `pnpm-workspace.yaml`, `.npmrc`, `pnpm-lock.yaml` | New | The JavaScript workspace and exact versions |
| `apps/{council-web,sim-console}/*` | New | The two placeholder React apps |
| `.gitignore`, `.editorconfig`, `.gitattributes`, `README.md` | New | Repo hygiene and quick start |
| `docs/ROADMAP.md` | Card marked ◐ | Required by `AGENTS.md` |

## How we know it works

| Done item on the card | Proof |
|---|---|
| `./gradlew build` passes | Ran it: build successful, all modules compile. |
| `pnpm -r build` passes | Ran it: both apps type-check and build. |
| Three health endpoints answer when run | Each app has `HealthEndpointTest`. It starts the whole app on a random port, calls `/health` with a real HTTP client, and expects 200 and `"status":"UP"`. We also ran `zamsim` by hand and fetched `/health` with `curl`. |
| Versions pinned exactly | Searched all build and package files for `^`, `~` and `+`: none. |

Not yet verified: a clean clone on a different Windows machine. That is the card's wording ("on a clean
clone"), so the reviewer should try it.

## Rebuild it yourself

1. Make an empty folder with `git init`. Make `settings.gradle.kts` listing one module. Get the build
   to pass with `gradle wrapper` and then `./gradlew build`.
2. Add a second module that depends on the first, using `project(":name")`.
3. Make a Spring Boot app in a third module. Add the actuator and make `/health` answer. Hint: the
   property is `management.endpoints.web.base-path`.
4. Write a test that starts it on a random port and checks the answer.
5. Make a `pnpm-workspace.yaml` with one Vite + React + TypeScript app and make `pnpm -r build` pass.
6. Check you used no `^` or `+` anywhere.

## Mistakes to avoid

- **Guessing versions.** We first wrote a JUnit version from memory. It was out of date. Look versions
  up.
- **Putting Spring in `msika-core` "just for annotations".** It breaks rule 7 and the later ArchUnit
  test fails the build.
- **Letting `msika-api` depend on `zamsim`.** It would be convenient and it breaks rule 6. Msika calls
  simulators over HTTP only.
- **Committing secrets.** The repo is public. `.gitignore` blocks `.env` and key files, but only
  careful review stops everything.
- **Wrong line endings on Windows.** `.gitattributes` forces LF so scripts also run on Linux.
