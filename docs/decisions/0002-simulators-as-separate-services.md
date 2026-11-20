# 0002: Simulators are separate services behind ports

Status: Accepted

## Context
We have no access to Zamtel Money or eLevy yet. We still need to build and demonstrate the whole
flow. If the simulators were just classes inside Msika, Msika would learn to rely on things a real
system never promises: instant answers, never failing, never repeating a message, sharing our
database.

## Decision
`zamsim` and `elevysim` are separate Spring Boot services with their own databases. Msika reaches them
only through ports defined in `msika-core` (`PaymentsPort`, `LevyAuthorityPort`) and adapters in
`msika-api`, over HTTP. Msika never imports simulator code and never reads a simulator database.
The simulators can be told to fail, delay and repeat callbacks (fault injection).

## Consequences
- Replacing a simulator with the real system is an adapter and configuration change, not a rewrite.
- Msika must handle slow, failing, duplicated and out-of-order responses from day one, which is what
  real integrations do.
- More moving parts locally: three services and a database. Docker Compose hides most of that.
- An ArchUnit rule fails the build if Msika code imports from a simulator.
