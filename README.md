# Msika

Msika records a market trader's cash day: closing-time deposits into a mobile money wallet, digital
sales, levy paid, and monthly turnover, all against a stall. Zamtel Money and eLevy are simulated by
our own services (`zamsim`, `elevysim`).

Start with `AGENTS.md`, then `docs/ROADMAP.md`, `docs/ARCHITECTURE.md` and `CONTRIBUTING.md`.
New to the code? Read `docs/learning/README.md`.

## What you need

- Java 21 (a JDK, not just a JRE)
- Node.js 22 or newer
- pnpm 10 (`npm install -g pnpm`)

You do **not** need to install Gradle. The `./gradlew` wrapper downloads the right version.

## Quick start

```bash
# Build and test the Java modules
./gradlew build

# Install and build the web apps
pnpm install
pnpm -r build
```

On Windows PowerShell use `.\gradlew build` instead of `./gradlew build`.

After cloning, run `scripts/setup.ps1` (or `scripts/setup.sh`) once. It turns on the pre-push hook,
which runs the fast checks before every push. To run the checks yourself, use
`./scripts/check.ps1 --fast`, or `./scripts/check.ps1` for everything (see `CONTRIBUTING.md`).

## Run things

| What | Command | Check |
|---|---|---|
| Msika API | `./gradlew :msika-api:bootRun` | http://localhost:8080/health |
| Zamtel Money simulator | `./gradlew :zamsim:bootRun` | http://localhost:8081/health |
| eLevy simulator | `./gradlew :elevysim:bootRun` | http://localhost:8082/health |
| Council dashboard | `pnpm --filter council-web dev` | http://localhost:5173 |
| Simulator console | `pnpm --filter sim-console dev` | http://localhost:5174 |

Each health endpoint answers `{"status":"UP"}`.

## Layout

```
platform/   Java modules: msika-ledger, msika-core, msika-api, zamsim, elevysim
apps/       Web apps: council-web, sim-console
docs/       Roadmap, architecture, SMS protocol, teaching pack
```

The full layout and the rules behind it are in `docs/ARCHITECTURE.md`.
